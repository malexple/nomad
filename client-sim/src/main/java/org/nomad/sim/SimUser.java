package org.nomad.sim;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.nomad.client.CursorState;
import org.nomad.client.Received;
import org.nomad.client.WsInbox;
import org.nomad.client.WsProtocol;
import org.nomad.core.DeviceAuth;
import org.nomad.core.Ids;

/** One emulated user: own key, one WebSocket connection to one node. */
final class SimUser implements WebSocket.Listener {
    final String name;
    final String url;
    final KeyPair keys = DeviceAuth.generateKeyPair();
    final CompletableFuture<Void> ready = new CompletableFuture<>();

    private final Stats stats;
    private final WsInbox inbox = new WsInbox(new CursorState());
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket ws;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    SimUser(String name, String url, Stats stats) {
        this.name = name;
        this.url = url;
        this.stats = stats;
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

    void sendEnvelope(String toMailbox, String payload) {
        send(WsProtocol.send(
                UUID.randomUUID(), toMailbox, 0, payload.getBytes(StandardCharsets.UTF_8), WsProtocol.defaultExpiryDay()));
    }

    void ping() {
        send(WsProtocol.ping());
    }

    void close() {
        WebSocket w = ws;
        if (w != null) {
            w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    private synchronized void send(String json) {
        tail = tail.thenCompose(x -> ws.sendText(json, true));
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
    public void onError(WebSocket w, Throwable error) {
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
                send(WsProtocol.sync(inbox.afterSeq(), 100));
            }
            case "wake" -> send(WsProtocol.sync(inbox.afterSeq(), 100));
            case "msgs" -> {
                WsInbox.Result r = inbox.onMsgs(WsProtocol.parseMsgs(n));
                for (Received x : r.fresh()) {
                    stats.onReceive(new String(x.payload(), StandardCharsets.UTF_8));
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
}
