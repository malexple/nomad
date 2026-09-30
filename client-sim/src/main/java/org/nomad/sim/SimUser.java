package org.nomad.sim;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
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
import org.nomad.crypto.Identity;

/** One emulated user: own key, one WebSocket connection to one node, optional end-to-end encryption. */
final class SimUser implements WebSocket.Listener {
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
    private final ConcurrentHashMap<String, CompletableFuture<PrekeyBundle>> bundles = new ConcurrentHashMap<>();
    private final WsInbox inbox = new WsInbox(new CursorState());
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket ws;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    SimUser(String name, String url, Stats stats, boolean encrypt) {
        this.name = name;
        this.url = url;
        this.stats = stats;
        this.encrypt = encrypt;
        this.identity = encrypt ? new Identity(keys) : null;
        this.convo = encrypt ? new ConversationManager(identity) : null;
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

    void connect(HttpClient http) {
        http.newWebSocketBuilder().buildAsync(URI.create(url), this).whenComplete((w, err) -> {
            if (err != null) {
                ready.completeExceptionally(err);
            }
        });
    }

    /** Sends one message; in encrypted mode the first message to a peer fetches its prekey bundle. */
    void sendMessage(SimUser to, String payload) throws Exception {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        int version = 0;
        if (encrypt) {
            String peer = to.uid();
            if (!convo.hasSession(peer)) {
                PrekeyBundle bundle = fetchBundle(peer);
                if (!convo.hasSession(peer)) {
                    convo.startSession(peer, bundle);
                }
            }
            data = convo.encryptFor(peer, data);
            version = 1;
        }
        send(WsProtocol.send(UUID.randomUUID(), to.mailboxId(), version, data, WsProtocol.defaultExpiryDay()));
    }

    private PrekeyBundle fetchBundle(String uid) throws Exception {
        CompletableFuture<PrekeyBundle> f = bundles.computeIfAbsent(uid, k -> new CompletableFuture<>());
        try {
            send(WsProtocol.pkGet(uid));
            return f.get(10, TimeUnit.SECONDS);
        } finally {
            bundles.remove(uid);
        }
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
            case "msgs" -> {
                WsInbox.Result r = inbox.onMsgs(WsProtocol.parseMsgs(n));
                received.addAndGet(r.fresh().size());
                for (Received x : r.fresh()) {
                    onPayload(x);
                }
                if (!r.fresh().isEmpty()) {
                    send(WsProtocol.ack(inbox.afterSeq()));
                }
                if (r.syncAgain()) {
                    send(WsProtocol.sync(inbox.afterSeq(), 100));
                }
            }
            case "ack" -> stats.sendAcks.incrementAndGet();
            case "acked", "pong" -> { }
            case "error" -> {
                stats.errors.incrementAndGet();
                System.err.println(name + ": server error " + n.path("msg").asText());
            }
            default -> System.err.println(name + ": unknown message " + msg);
        }
    }

    private void onPayload(Received x) {
        String text = null;
        if (x.version() == 0) {
            text = new String(x.payload(), StandardCharsets.UTF_8);
        } else if (x.version() == 1 && convo != null) {
            Optional<ConversationManager.Decrypted> d = convo.decrypt(x.payload());
            if (d.isPresent()) {
                text = new String(d.get().plaintext(), StandardCharsets.UTF_8);
            }
        }
        if (text == null) {
            stats.undecryptable.incrementAndGet();
        } else {
            stats.onReceive(text);
        }
    }
}
