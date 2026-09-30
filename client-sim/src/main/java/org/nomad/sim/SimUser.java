package org.nomad.sim;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.nomad.client.CursorState;
import org.nomad.client.Received;
import org.nomad.client.WsInbox;
import org.nomad.client.WsProtocol;
import org.nomad.core.DeviceAuth;
import org.nomad.core.Ids;
import org.nomad.core.PrekeyBundle;
import org.nomad.crypto.ConversationManager;
import org.nomad.crypto.GroupManager;
import org.nomad.crypto.Identity;

/**
 * One emulated user: own key, one WebSocket connection to one node, optional end-to-end encryption and groups.
 * All blocking work (prekey fetches, sends) runs on the user's own single io thread, never on the WebSocket
 * listener thread, so the listener can always deliver the replies that the io thread is waiting for.
 * When the node answers "rate_limited" the sends are queued and repeated slowly (below the node's limit),
 * the prekey lookups wait for the given time.
 */
final class SimUser implements WebSocket.Listener {
    private static final ScheduledExecutorService BACKOFF = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sim-backoff");
        t.setDaemon(true);
        return t;
    });
    private static final long DRAIN_INTERVAL_MS = 60;

    private static final class RateLimited extends RuntimeException {
        final long retryMs;

        RateLimited(long retryMs) {
            super("rate limited", null, false, false);
            this.retryMs = retryMs;
        }
    }

    final String name;
    final String url;
    final KeyPair keys = DeviceAuth.generateKeyPair();
    final CompletableFuture<Void> ready = new CompletableFuture<>();
    final CompletableFuture<Void> published = new CompletableFuture<>();
    final AtomicInteger received = new AtomicInteger();
    volatile String closedInfo;

    private final Stats stats;
    private final boolean encrypt;
    private final Identity identity;
    private final ConversationManager convo;
    private final GroupManager groups;
    private final ExecutorService io;
    private final ConcurrentHashMap<String, CompletableFuture<PrekeyBundle>> bundles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> inflight = new ConcurrentHashMap<>();
    private final ArrayDeque<String> backlog = new ArrayDeque<>();
    private final Set<String> backlogged = new HashSet<>();
    private boolean draining;
    private final WsInbox inbox = new WsInbox(new CursorState());
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket ws;
    private long lastAcked;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    SimUser(String name, String url, Stats stats, boolean encrypt) {
        this.name = name;
        this.url = url;
        this.stats = stats;
        this.encrypt = encrypt;
        this.identity = encrypt ? new Identity(keys) : null;
        this.convo = encrypt ? new ConversationManager(identity) : null;
        this.groups = encrypt ? new GroupManager(identity) : null;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sim-io-" + name);
            t.setDaemon(true);
            return t;
        });
        if (!encrypt) {
            published.complete(null);
        }
    }

    String uid() {
        return Ids.uid(DeviceAuth.rawPublicKey(keys.getPublic()));
    }

    String mailboxId() {
        return Ids.mailboxIdFor(DeviceAuth.rawPublicKey(keys.getPublic()));
    }

    GroupManager.GroupMember asMember() {
        return new GroupManager.GroupMember(DeviceAuth.rawPublicKey(keys.getPublic()));
    }

    int heldCount() {
        return inbox.heldCount();
    }

    int unacknowledgedSends() {
        return inflight.size();
    }

    void connect(HttpClient http) {
        http.newWebSocketBuilder().buildAsync(URI.create(url), this).whenComplete((w, err) -> {
            if (err != null) {
                ready.completeExceptionally(err);
            }
        });
    }

    // ------------------------------------------------------------------ sending (io thread)

    CompletableFuture<Void> sendChat(SimUser to, int index) {
        return CompletableFuture.runAsync(() -> {
            try {
                String text = "sim|" + name + "|" + to.name + "|" + index + "|" + System.currentTimeMillis();
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                if (!encrypt) {
                    sendEnvelope(to.mailboxId(), 0, data);
                    return;
                }
                sendPairwise(to.uid(), to.mailboxId(), prefixed(GroupManager.KIND_CHAT, data));
            } catch (Exception e) {
                failed("chat", e);
            }
        }, io);
    }

    CompletableFuture<String> createGroup(List<SimUser> others) {
        return CompletableFuture.supplyAsync(() -> {
            GroupManager.CreatedGroup g = groups.createGroup(others.stream().map(SimUser::asMember).toList());
            sendOutbound(g.outbound());
            return g.groupId();
        }, io);
    }

    CompletableFuture<Void> sendGroup(String groupId, int index) {
        return CompletableFuture.runAsync(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!groups.canSend(groupId)) {
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("group state never arrived");
                    }
                    Thread.sleep(20);
                }
                String text = "sim|" + name + "|G|" + index + "|" + System.currentTimeMillis();
                GroupManager.Sealed s = groups.encrypt(groupId, text.getBytes(StandardCharsets.UTF_8));
                for (GroupManager.GroupMember m : s.recipients()) {
                    sendEnvelope(m.mailboxId(), 2, s.wire());
                }
            } catch (Exception e) {
                failed("group", e);
            }
        }, io);
    }

    private void sendOutbound(List<GroupManager.Outbound> out) {
        for (GroupManager.Outbound o : out) {
            try {
                sendPairwise(o.toUid(), o.toMailbox(), o.plaintext());
            } catch (Exception e) {
                failed("control", e);
            }
        }
    }

    private void sendPairwise(String peerUid, String peerMailbox, byte[] plaintext) throws Exception {
        if (!convo.hasSession(peerUid)) {
            PrekeyBundle bundle = fetchBundle(peerUid);
            if (!convo.hasSession(peerUid)) {
                convo.startSession(peerUid, bundle);
            }
        }
        sendEnvelope(peerMailbox, 1, convo.encryptFor(peerUid, plaintext));
    }

    private PrekeyBundle fetchBundle(String uid) throws Exception {
        for (int attempt = 0; ; attempt++) {
            CompletableFuture<PrekeyBundle> f = bundles.computeIfAbsent(uid, k -> new CompletableFuture<>());
            try {
                send(WsProtocol.pkGet(uid));
                return f.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RateLimited r && attempt < 8) {
                    Thread.sleep(r.retryMs + 20);
                    continue;
                }
                throw e;
            } finally {
                bundles.remove(uid);
            }
        }
    }

    /** Remembers the envelope until the node acknowledges it, so that a rate-limited send can be repeated. */
    private void sendEnvelope(String mailbox, int version, byte[] data) {
        UUID id = UUID.randomUUID();
        String json = WsProtocol.send(id, mailbox, version, data, WsProtocol.defaultExpiryDay());
        inflight.put(id.toString(), json);
        send(json);
    }

    private synchronized void onRateLimitedSend(String id, long retryMs) {
        stats.rateLimited.incrementAndGet();
        if (inflight.containsKey(id) && backlogged.add(id)) {
            backlog.add(id);
        }
        if (!draining) {
            draining = true;
            BACKOFF.schedule(this::drain, retryMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Repeats queued sends one by one, slower than the node's limit. */
    private void drain() {
        String json = null;
        synchronized (this) {
            String id = backlog.poll();
            if (id == null) {
                draining = false;
                return;
            }
            backlogged.remove(id);
            json = inflight.get(id);
        }
        if (json != null) {
            send(json);
        }
        BACKOFF.schedule(this::drain, DRAIN_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static byte[] prefixed(byte kind, byte[] data) {
        byte[] out = new byte[data.length + 1];
        out[0] = kind;
        System.arraycopy(data, 0, out, 1, data.length);
        return out;
    }

    private void failed(String what, Exception e) {
        stats.errors.incrementAndGet();
        System.err.println(name + ": " + what + " failed: " + e);
    }

    void ping() {
        send(WsProtocol.ping());
    }

    /** Safety net: reads by cursor even if a wake signal was lost. */
    void syncNow() {
        send(WsProtocol.sync(inbox.afterSeq(), 100));
    }

    void close() {
        WebSocket w = ws;
        if (w != null) {
            w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    private synchronized void send(String json) {
        tail = tail.thenCompose(x -> ws.sendText(json, true)).exceptionally(err -> {
            closedInfo = "SEND FAILED " + err;
            return null;
        });
    }

    // ------------------------------------------------------------------ receiving (listener thread)

    @Override
    public void onOpen(WebSocket w) {
        this.ws = w;
        w.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String msg = partial.toString();
            partial.setLength(0);
            try {
                handle(msg);
            } catch (RuntimeException e) {
                stats.errors.incrementAndGet();
                System.err.println(name + ": " + e);
            }
        }
        w.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
        closedInfo = "CLOSED by peer " + statusCode + " " + reason;
        System.err.println(name + ": " + closedInfo);
        return null;
    }

    @Override
    public void onError(WebSocket w, Throwable error) {
        closedInfo = "ERROR " + error;
        stats.errors.incrementAndGet();
        ready.completeExceptionally(error);
        System.err.println(name + ": websocket error " + error);
    }

    private void handle(String msg) {
        JsonNode n = WsProtocol.parse(msg);
        switch (n.path("t").asText()) {
            case "challenge" -> send(WsProtocol.auth(keys, n.path("nonce").asText()));
            case "auth_ok" -> {
                ready.complete(null);
                if (encrypt) {
                    send(WsProtocol.pkPut(identity.publicBundle(), identity.generateOneTimePrekeys(20)));
                }
                send(WsProtocol.sync(inbox.afterSeq(), 100));
            }
            case "pk_ok" -> published.complete(null);
            case "pk" -> bundles
                    .computeIfAbsent(n.path("uid").asText(), k -> new CompletableFuture<>())
                    .complete(WsProtocol.parseBundle(n.path("bundle")));
            case "pk_none" -> bundles
                    .computeIfAbsent(n.path("uid").asText(), k -> new CompletableFuture<>())
                    .completeExceptionally(new IllegalStateException("no prekey bundle for " + n.path("uid").asText()));
            case "wake" -> send(WsProtocol.sync(inbox.afterSeq(), 100));
            case "msgs" -> onMsgs(n);
            case "ack" -> {
                inflight.remove(n.path("id").asText());
                stats.sendAcks.incrementAndGet();
            }
            case "acked", "pong" -> { }
            case "error" -> onServerError(n);
            default -> System.err.println(name + ": unknown message " + msg);
        }
    }

    private void onServerError(JsonNode n) {
        if (!"rate_limited".equals(n.path("msg").asText())) {
            stats.errors.incrementAndGet();
            System.err.println(name + ": server error " + n.path("msg").asText());
            return;
        }
        long retry = n.path("retryMs").asLong(100);
        if (n.hasNonNull("uid")) {
            stats.rateLimited.incrementAndGet();
            bundles.computeIfAbsent(n.path("uid").asText(), k -> new CompletableFuture<>())
                    .completeExceptionally(new RateLimited(retry));
        } else if (n.hasNonNull("id")) {
            onRateLimitedSend(n.path("id").asText(), retry);
        } else {
            stats.rateLimited.incrementAndGet();
            lastAcked = 0;
            BACKOFF.schedule(this::syncNow, retry, TimeUnit.MILLISECONDS);
        }
    }

    private void onMsgs(JsonNode n) {
        WsInbox.Result r = inbox.onMsgs(WsProtocol.parseMsgs(n));
        for (Received x : r.fresh()) {
            if (!process(x)) {
                inbox.hold(x);
            }
        }
        retryHeld();
        long ack = inbox.safeAckSeq();
        if (ack > lastAcked) {
            send(WsProtocol.ack(ack));
            lastAcked = ack;
        }
        if (r.syncAgain()) {
            send(WsProtocol.sync(inbox.afterSeq(), 100));
        }
    }

    /** @return false if the message cannot be processed yet (keys may still be on the way) */
    private boolean process(Received x) {
        if (x.version() == 0) {
            deliver(new String(x.payload(), StandardCharsets.UTF_8));
            return true;
        }
        if (x.version() == 1 && convo != null) {
            Optional<ConversationManager.Decrypted> d = convo.decrypt(x.payload());
            if (d.isEmpty()) {
                return false;
            }
            byte[] plain = d.get().plaintext();
            if (plain.length == 0) {
                return true;
            }
            if (plain[0] == GroupManager.KIND_CHAT) {
                deliver(new String(plain, 1, plain.length - 1, StandardCharsets.UTF_8));
                return true;
            }
            List<GroupManager.Outbound> follow = groups.handleControl(d.get().peerUid(), plain);
            if (!follow.isEmpty()) {
                io.execute(() -> sendOutbound(follow));
            }
            return true;
        }
        if (x.version() == 2 && groups != null) {
            Optional<GroupManager.GroupDecrypted> g = groups.decrypt(x.payload());
            if (g.isEmpty()) {
                return false;
            }
            deliver(new String(g.get().plaintext(), StandardCharsets.UTF_8));
            return true;
        }
        return false;
    }

    private void deliver(String text) {
        received.incrementAndGet();
        stats.onReceive(text, name);
    }

    private void retryHeld() {
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Received h : inbox.heldItems()) {
                if (process(h)) {
                    inbox.release(h);
                    progress = true;
                }
            }
        }
    }
}
