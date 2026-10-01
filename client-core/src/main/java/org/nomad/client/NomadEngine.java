package org.nomad.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.nomad.core.DeviceKeyPair;
import org.nomad.core.Ids;
import org.nomad.core.OneTimePrekey;
import org.nomad.core.PrekeyBundle;
import org.nomad.crypto.ConversationManager;
import org.nomad.crypto.GroupManager;
import org.nomad.crypto.Identity;

/**
 * The client logic that is the same on every platform: connection with automatic reconnect, challenge-response
 * authentication, prekey upload, sync and acknowledgements, pairwise and group messages, an outbox of encrypted
 * envelopes, held messages, rate-limit back-off.
 *
 * Threading: everything runs on ONE internal thread. The public methods and the transport callbacks only post work to
 * it, so the engine needs no locks and never blocks. Listener methods are called on that thread: keep them short or
 * hand the work over to your own thread.
 *
 * Rules that are built in (docs/state.md):
 * - the state is saved BEFORE a ciphertext leaves the device (it is first put into the outbox, then the state is saved,
 *   then the frame is sent), so a crash can never make a message key encrypt a second message;
 * - the node is told "acknowledged up to N" only AFTER the state that contains the effect of those messages is saved;
 * - a message that cannot be decrypted yet is held, and the acknowledgement stops below it.
 */
public final class NomadEngine {
    public enum ConnectionState { OFFLINE, CONNECTING, AUTHENTICATING, ONLINE }

    /** Called on the engine thread. */
    public interface Listener {
        default void onConnectionState(ConnectionState state) {}

        default void onPrekeysPublished() {}

        /** A direct message. peerSigKey identifies the sender (the mailbox for the answer is derived from it). */
        default void onChat(String peerUid, byte[] peerSigKey, byte[] text) {}

        default void onGroupMessage(String groupId, String senderUid, byte[] text) {}

        /** A group was created, changed or removed: read GroupManager.listGroups() again. */
        default void onGroupsChanged() {}

        default void onSendFailed(String reason) {}

        default void onError(String message) {}
    }

    /**
     * syncIntervalMs and pingIntervalMs: 0 switches the timer off. sendIntervalMs: minimal gap between two frames sent from
     * the outbox, 0 = as fast as possible (after a "rate_limited" answer the engine slows down by itself).
     */
    public record Config(
            long syncIntervalMs, long pingIntervalMs, long sendIntervalMs, long reconnectMinMs, long reconnectMaxMs) {
        public static Config defaults() {
            return new Config(60_000, 30_000, 50, 1_000, 30_000);
        }
    }

    private static final long ADAPTIVE_SEND_INTERVAL_MS = 55;
    private static final int MAX_BUNDLE_ATTEMPTS = 8;

    private record PendingPlain(String mailbox, byte[] plaintext, CompletableFuture<Void> done) {}

    private record PendingGroupSend(byte[] text, CompletableFuture<Void> done) {}

    private record Item(String mailbox, int version, byte[] data) {}

    private final ClientState state;
    private final Identity identity;
    private final ConversationManager convo;
    private final GroupManager groups;
    private final WsInbox inbox;
    private final DeviceKeyPair keys;
    private final Persistence persistence;
    private final Transport transport;
    private final String url;
    private final Listener listener;
    private final Config cfg;
    private final ScheduledExecutorService exec;

    // Everything below is touched only on the engine thread (except the volatile mirrors).
    private boolean running;
    private int generation;
    private long backoffMs;
    private long lastAcked;
    private long adaptiveSendIntervalMs;
    private boolean pacerRunning;
    private boolean dirty;
    private boolean saveScheduled;
    private boolean controlChanged;
    private ConnectionState connState = ConnectionState.OFFLINE;
    private ScheduledFuture<?> syncTask;
    private ScheduledFuture<?> pingTask;
    private final Map<String, List<PendingPlain>> waitingForBundle = new HashMap<>();
    private final Map<String, Integer> bundleAttempts = new HashMap<>();
    private final Set<String> bundleRequested = new HashSet<>();
    private final Map<String, List<PendingGroupSend>> groupSendQueue = new HashMap<>();
    private final Set<String> sentThisConnection = new HashSet<>();
    private final ArrayDeque<String> sendQueue = new ArrayDeque<>();

    private volatile ConnectionState connStateMirror = ConnectionState.OFFLINE;
    private volatile int outboxCount;
    private final AtomicInteger bundleRequestCount = new AtomicInteger();
    private final AtomicInteger rateLimitCount = new AtomicInteger();

    public NomadEngine(
            ClientState state, Persistence persistence, Transport transport, String url, Listener listener, Config cfg) {
        this.state = state;
        this.identity = state.identity;
        this.convo = state.conversations;
        this.groups = state.groups;
        this.inbox = state.inbox;
        this.keys = state.identity.deviceKeys();
        this.persistence = persistence;
        this.transport = transport;
        this.url = url;
        this.listener = listener;
        this.cfg = cfg;
        this.outboxCount = state.engine.outbox().size();
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nomad-engine");
            t.setDaemon(true);
            return t;
        });
    }

    // ================================================================ public API (any thread)

    public String uid() {
        return identity.uid();
    }

    public byte[] sigKey() {
        return identity.sigPub();
    }

    public ConnectionState connectionState() {
        return connStateMirror;
    }

    public int outboxSize() {
        return outboxCount;
    }

    public int heldCount() {
        return inbox.heldCount();
    }

    public boolean isPrekeysPublished() {
        return state.engine.prekeysPublished();
    }

    /** How many times a prekey bundle was requested from the node (a restored device must not need new ones). */
    public int bundleRequestCount() {
        return bundleRequestCount.get();
    }

    /** How many times the node answered "rate_limited" (the engine then slows down by itself). */
    public int rateLimitedCount() {
        return rateLimitCount.get();
    }

    public List<GroupManager.GroupInfo> groups() {
        return groups.listGroups();
    }

    /** Starts connecting; the engine reconnects by itself until stop() or shutdown(). */
    public void start() {
        post(() -> {
            if (running) {
                return;
            }
            running = true;
            connect();
        });
    }

    public void stop() {
        post(this::stopNow);
    }

    /** Stops, writes pending changes and ends the engine thread. */
    public CompletableFuture<Void> shutdown() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        post(() -> {
            stopNow();
            if (dirty) {
                persistNow();
            }
            done.complete(null);
            exec.shutdownNow();
        });
        return done;
    }

    /** The sealed state, taken on the engine thread (consistent, no message is being processed). */
    public CompletableFuture<byte[]> snapshot(byte[] masterKey) {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        post(() -> {
            try {
                f.complete(state.seal(masterKey));
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    /**
     * Sends a direct message. The future completes when the encrypted envelope is in the outbox and the state is saved
     * (from then on the message is safe), not when it has been delivered.
     */
    public CompletableFuture<Void> sendChat(byte[] peerSigKey, byte[] text) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        post(() -> {
            try {
                sendPairwise(
                        Ids.uid(peerSigKey), Ids.mailboxIdFor(peerSigKey), prefixed(GroupManager.KIND_CHAT, text), done);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    /** Creates a group with this device as the admin; completes with the group id once the invitations are queued. */
    public CompletableFuture<String> createGroup(List<GroupManager.GroupMember> others) {
        CompletableFuture<String> done = new CompletableFuture<>();
        post(() -> {
            try {
                GroupManager.CreatedGroup g = groups.createGroup(others);
                if (!persistNow()) {
                    throw new IOException("cannot save the new group");
                }
                for (GroupManager.Outbound o : g.outbound()) {
                    sendPairwise(o.toUid(), o.toMailbox(), o.plaintext(), null);
                }
                safe(listener::onGroupsChanged);
                done.complete(g.groupId());
            } catch (IOException | RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    public CompletableFuture<String> createGroup(String name, List<GroupManager.GroupMember> others) {
        CompletableFuture<String> done = new CompletableFuture<>();
        post(() -> {
            try {
                GroupManager.CreatedGroup g = groups.createGroup(name, others);
                if (!persistNow()) {
                    throw new IOException("cannot save the new group");
                }
                for (GroupManager.Outbound o : g.outbound()) {
                    sendPairwise(o.toUid(), o.toMailbox(), o.plaintext(), null);
                }
                safe(listener::onGroupsChanged);
                done.complete(g.groupId());
            } catch (IOException | RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    /** Sends a group message; waits (in memory) until the group's keys exist on this device. */
    public CompletableFuture<Void> sendGroup(String groupId, byte[] text) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        post(() -> {
            if (groups.canSend(groupId)) {
                doSendGroup(groupId, text, done);
            } else {
                groupSendQueue.computeIfAbsent(groupId, k -> new ArrayList<>()).add(new PendingGroupSend(text, done));
            }
        });
        return done;
    }

    // ================================================================ connection

    private void post(Runnable r) {
        try {
            exec.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    safe(() -> listener.onError("internal error: " + e));
                }
            });
        } catch (RejectedExecutionException ignored) {
            // the engine has been shut down
        }
    }

    private void schedule(Runnable r, long delayMs) {
        try {
            exec.schedule(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    safe(() -> listener.onError("internal error: " + e));
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // the engine has been shut down
        }
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // a faulty listener must not break the engine
        }
    }

    private void setState(ConnectionState s) {
        if (connState != s) {
            connState = s;
            connStateMirror = s;
            safe(() -> listener.onConnectionState(s));
        }
    }

    private void connect() {
        if (!running) {
            return;
        }
        final int gen = ++generation;
        setState(ConnectionState.CONNECTING);
        try {
            transport.connect(url, new TransportListener() {
                @Override
                public void onConnected() {
                    // nothing to do: the node speaks first (challenge)
                }

                @Override
                public void onText(String text) {
                    post(() -> {
                        if (gen == generation) {
                            onFrame(text);
                        }
                    });
                }

                @Override
                public void onClosed(String reason) {
                    post(() -> {
                        if (gen == generation) {
                            onTransportClosed(reason);
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            onTransportClosed("cannot connect: " + e);
        }
    }

    private void stopNow() {
        running = false;
        generation++;
        cancelTimers();
        try {
            transport.close();
        } catch (RuntimeException ignored) {
            // closing is best effort
        }
        resetConnectionState();
        setState(ConnectionState.OFFLINE);
    }

    private void onTransportClosed(String reason) {
        cancelTimers();
        resetConnectionState();
        setState(ConnectionState.OFFLINE);
        if (!running) {
            return;
        }
        backoffMs = backoffMs == 0 ? cfg.reconnectMinMs() : Math.min(backoffMs * 2, cfg.reconnectMaxMs());
        final int gen = generation;
        schedule(() -> {
            if (running && gen == generation) {
                connect();
            }
        }, backoffMs);
    }

    private void resetConnectionState() {
        sentThisConnection.clear();
        sendQueue.clear();
        bundleRequested.clear();
        pacerRunning = false;
        lastAcked = 0;
        adaptiveSendIntervalMs = 0;
    }

    private void cancelTimers() {
        if (syncTask != null) {
            syncTask.cancel(false);
            syncTask = null;
        }
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
    }

    private void startTimers() {
        cancelTimers();
        if (cfg.syncIntervalMs() > 0) {
            syncTask = exec.scheduleWithFixedDelay(
                    () -> post(this::sendSync), cfg.syncIntervalMs(), cfg.syncIntervalMs(), TimeUnit.MILLISECONDS);
        }
        if (cfg.pingIntervalMs() > 0) {
            pingTask = exec.scheduleWithFixedDelay(
                    () -> post(() -> sendFrame(WsProtocol.ping())),
                    cfg.pingIntervalMs(),
                    cfg.pingIntervalMs(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private boolean sendFrame(String json) {
        try {
            return transport.send(json);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void sendSync() {
        if (connState == ConnectionState.ONLINE) {
            sendFrame(WsProtocol.sync(inbox.afterSeq(), 100));
        }
    }

    // ================================================================ frames from the node

    private void onFrame(String text) {
        JsonNode n;
        try {
            n = WsProtocol.parse(text);
        } catch (IllegalArgumentException e) {
            safe(() -> listener.onError("unreadable frame from the node"));
            return;
        }
        switch (n.path("t").asText()) {
            case "challenge" -> {
                setState(ConnectionState.AUTHENTICATING);
                sendFrame(WsProtocol.auth(keys, n.path("nonce").asText()));
            }
            case "auth_ok" -> onAuthOk();
            case "pk_ok" -> {
                state.engine.setPrekeysPublished(true);
                markDirty();
                safe(listener::onPrekeysPublished);
            }
            case "pk" -> onBundle(n);
            case "pk_none" -> onNoBundle(n.path("uid").asText());
            case "wake" -> sendSync();
            case "msgs" -> onMsgs(n);
            case "ack" -> onSendAck(n.path("id").asText());
            case "acked", "pong" -> { }
            case "error" -> onServerError(n);
            default -> safe(() -> listener.onError("unknown frame: " + n.path("t").asText()));
        }
    }

    private void onAuthOk() {
        backoffMs = 0;
        setState(ConnectionState.ONLINE);
        if (state.engine.prekeysPublished()) {
            safe(listener::onPrekeysPublished);
        } else {
            List<OneTimePrekey> opks = identity.generateOneTimePrekeys(20);
            if (persistNow()) {
                sendFrame(WsProtocol.pkPut(identity.publicBundle(), opks));
            }
        }
        sendSync();
        startTimers();
        for (String uid : new ArrayList<>(waitingForBundle.keySet())) {
            requestBundle(uid);
        }
        queueOutbox();
    }

    private void onServerError(JsonNode n) {
        String msg = n.path("msg").asText();
        if (!"rate_limited".equals(msg)) {
            safe(() -> listener.onError("node: " + msg));
            return;
        }
        long retry = n.path("retryMs").asLong(100);
        rateLimitCount.incrementAndGet();
        adaptiveSendIntervalMs = ADAPTIVE_SEND_INTERVAL_MS;
        if (n.hasNonNull("uid")) {
            String uid = n.path("uid").asText();
            bundleRequested.remove(uid);
            schedule(() -> requestBundle(uid), retry + 20);
        } else if (n.hasNonNull("id")) {
            String id = n.path("id").asText();
            sentThisConnection.remove(id);
            if (state.engine.outbox().containsKey(id) && !sendQueue.contains(id)) {
                sendQueue.addLast(id);
            }
            schedule(this::ensurePump, retry);
        } else {
            lastAcked = 0;
            schedule(this::sendSync, retry);
        }
    }

    // ================================================================ receiving

    private void onMsgs(JsonNode n) {
        WsInbox.Result r = inbox.onMsgs(WsProtocol.parseMsgs(n));
        for (Received x : r.fresh()) {
            if (!process(x)) {
                inbox.hold(x);
            }
        }
        retryHeld();
        if (controlChanged) {
            controlChanged = false;
            flushGroupSends();
            safe(listener::onGroupsChanged);
        }
        long ack = inbox.safeAckSeq();
        if (ack > lastAcked) {
            // the effects of these messages must be on disk before the node may delete them
            if (persistNow()) {
                sendFrame(WsProtocol.ack(ack));
                lastAcked = ack;
            }
        } else if (!r.fresh().isEmpty()) {
            markDirty();
        }
        if (r.syncAgain()) {
            sendSync();
        }
    }

    /** @return false if the message cannot be processed yet (its keys may still be on the way) */
    private boolean process(Received x) {
        if (x.version() == 1) {
            Optional<ConversationManager.Decrypted> d = convo.decrypt(x.payload());
            if (d.isEmpty()) {
                return false;
            }
            byte[] plain = d.get().plaintext();
            if (plain.length == 0) {
                return true;
            }
            String peerUid = d.get().peerUid();
            if (plain[0] == GroupManager.KIND_CHAT) {
                byte[] text = Arrays.copyOfRange(plain, 1, plain.length);
                safe(() -> listener.onChat(peerUid, d.get().peerSigKey(), text));
                return true;
            }
            List<GroupManager.Outbound> follow = groups.handleControl(peerUid, plain);
            controlChanged = true;
            for (GroupManager.Outbound o : follow) {
                sendPairwise(o.toUid(), o.toMailbox(), o.plaintext(), null);
            }
            return true;
        }
        if (x.version() == 2) {
            Optional<GroupManager.GroupDecrypted> g = groups.decrypt(x.payload());
            if (g.isEmpty()) {
                return false;
            }
            safe(() -> listener.onGroupMessage(g.get().groupId(), g.get().senderUid(), g.get().plaintext()));
            return true;
        }
        safe(() -> listener.onError("dropped a message of an unknown scheme version " + x.version()));
        return true;
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

    // ================================================================ sending

    private static byte[] prefixed(byte kind, byte[] data) {
        byte[] out = new byte[data.length + 1];
        out[0] = kind;
        System.arraycopy(data, 0, out, 1, data.length);
        return out;
    }

    private void sendPairwise(String uid, String mailbox, byte[] plaintext, CompletableFuture<Void> done) {
        if (convo.hasSession(uid)) {
            finishPairwise(uid, mailbox, plaintext, done);
            return;
        }
        waitingForBundle.computeIfAbsent(uid, k -> new ArrayList<>()).add(new PendingPlain(mailbox, plaintext, done));
        requestBundle(uid);
    }

    private void finishPairwise(String uid, String mailbox, byte[] plaintext, CompletableFuture<Void> done) {
        try {
            byte[] wire = convo.encryptFor(uid, plaintext);
            queueEnvelopes(List.of(new Item(mailbox, 1, wire)));
            if (done != null) {
                done.complete(null);
            }
        } catch (IOException | RuntimeException e) {
            if (done != null) {
                done.completeExceptionally(e);
            }
            safe(() -> listener.onSendFailed("message to " + uid + " not sent: " + e));
        }
    }

    private void doSendGroup(String groupId, byte[] text, CompletableFuture<Void> done) {
        try {
            GroupManager.Sealed s = groups.encrypt(groupId, text);
            List<Item> items = new ArrayList<>();
            for (GroupManager.GroupMember m : s.recipients()) {
                items.add(new Item(m.mailboxId(), 2, s.wire()));
            }
            queueEnvelopes(items);
            done.complete(null);
        } catch (IOException | RuntimeException e) {
            done.completeExceptionally(e);
            safe(() -> listener.onSendFailed("group message not sent: " + e));
        }
    }

    private void flushGroupSends() {
        for (String gid : new ArrayList<>(groupSendQueue.keySet())) {
            if (groups.canSend(gid)) {
                List<PendingGroupSend> list = groupSendQueue.remove(gid);
                for (PendingGroupSend p : list) {
                    doSendGroup(gid, p.text(), p.done());
                }
            }
        }
    }

    private void requestBundle(String uid) {
        if (connState != ConnectionState.ONLINE || !waitingForBundle.containsKey(uid) || !bundleRequested.add(uid)) {
            return;
        }
        int attempt = bundleAttempts.merge(uid, 1, Integer::sum);
        if (attempt > MAX_BUNDLE_ATTEMPTS) {
            bundleRequested.remove(uid);
            failWaiting(uid, "too many attempts to get the keys of " + uid);
            return;
        }
        bundleRequestCount.incrementAndGet();
        if (!sendFrame(WsProtocol.pkGet(uid))) {
            bundleRequested.remove(uid);
        }
    }

    private void onBundle(JsonNode n) {
        String uid = n.path("uid").asText();
        bundleRequested.remove(uid);
        bundleAttempts.remove(uid);
        List<PendingPlain> waiting = waitingForBundle.remove(uid);
        try {
            PrekeyBundle bundle = WsProtocol.parseBundle(n.path("bundle"));
            if (!convo.hasSession(uid)) {
                convo.startSession(uid, bundle);
            }
        } catch (RuntimeException e) {
            if (waiting != null) {
                for (PendingPlain p : waiting) {
                    if (p.done() != null) {
                        p.done().completeExceptionally(e);
                    }
                }
            }
            safe(() -> listener.onSendFailed("bad keys of " + uid + ": " + e));
            return;
        }
        if (waiting != null) {
            for (PendingPlain p : waiting) {
                finishPairwise(uid, p.mailbox(), p.plaintext(), p.done());
            }
        }
    }

    private void onNoBundle(String uid) {
        bundleRequested.remove(uid);
        failWaiting(uid, "the node has no keys for " + uid + " (has that device ever been online?)");
    }

    private void failWaiting(String uid, String reason) {
        bundleAttempts.remove(uid);
        List<PendingPlain> waiting = waitingForBundle.remove(uid);
        if (waiting != null) {
            for (PendingPlain p : waiting) {
                if (p.done() != null) {
                    p.done().completeExceptionally(new IllegalStateException(reason));
                }
            }
        }
        safe(() -> listener.onSendFailed(reason));
    }

    /**
     * Puts the encrypted envelopes into the outbox and SAVES the state before anything is sent:
     * the message keys have already been spent, so the result must survive a crash.
     */
    private void queueEnvelopes(List<Item> items) throws IOException {
        List<String> ids = new ArrayList<>();
        for (Item it : items) {
            UUID id = UUID.randomUUID();
            state.engine.outbox().put(
                    id.toString(),
                    WsProtocol.send(id, it.mailbox(), it.version(), it.data(), WsProtocol.defaultExpiryDay()));
            ids.add(id.toString());
        }
        outboxCount = state.engine.outbox().size();
        if (!persistNow()) {
            ids.forEach(state.engine.outbox()::remove);
            outboxCount = state.engine.outbox().size();
            throw new IOException("cannot save the state");
        }
        sendQueue.addAll(ids);
        ensurePump();
    }

    private void queueOutbox() {
        for (String id : state.engine.outbox().keySet()) {
            if (!sentThisConnection.contains(id) && !sendQueue.contains(id)) {
                sendQueue.addLast(id);
            }
        }
        ensurePump();
    }

    private void ensurePump() {
        if (!pacerRunning && connState == ConnectionState.ONLINE) {
            pacerRunning = true;
            post(this::pump);
        }
    }

    private void pump() {
        if (connState != ConnectionState.ONLINE) {
            pacerRunning = false;
            return;
        }
        long interval = Math.max(cfg.sendIntervalMs(), adaptiveSendIntervalMs);
        if (interval <= 0) {
            String id;
            while ((id = sendQueue.poll()) != null) {
                sendOutboxEntry(id);
            }
            pacerRunning = false;
            return;
        }
        String id = sendQueue.poll();
        if (id == null) {
            pacerRunning = false;
            return;
        }
        sendOutboxEntry(id);
        schedule(this::pump, interval);
    }

    private void sendOutboxEntry(String id) {
        String frame = state.engine.outbox().get(id);
        if (frame != null && sentThisConnection.add(id)) {
            if (!sendFrame(frame)) {
                sentThisConnection.remove(id);
            }
        }
    }

    private void onSendAck(String id) {
        if (state.engine.outbox().remove(id) != null) {
            outboxCount = state.engine.outbox().size();
            markDirty();
        }
        sentThisConnection.remove(id);
    }

    // ================================================================ saving

    private boolean persistNow() {
        try {
            persistence.save();
            dirty = false;
            return true;
        } catch (IOException | RuntimeException e) {
            safe(() -> listener.onError("cannot save the state: " + e));
            return false;
        }
    }

    /** Changes that need no hurry (an acknowledged envelope leaves the outbox) are saved a moment later. */
    private void markDirty() {
        dirty = true;
        if (!saveScheduled) {
            saveScheduled = true;
            schedule(() -> {
                saveScheduled = false;
                if (dirty) {
                    persistNow();
                }
            }, 300);
        }
    }
}
