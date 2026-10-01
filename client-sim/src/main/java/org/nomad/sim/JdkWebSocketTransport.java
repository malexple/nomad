package org.nomad.sim;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.nomad.client.Transport;
import org.nomad.client.TransportListener;

/** The transport of the simulator: the WebSocket of the JDK. Frames of one connection are sent strictly in order. */
final class JdkWebSocketTransport implements Transport {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private volatile WebSocket ws;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    @Override
    public void connect(String url, TransportListener listener) {
        close();
        synchronized (this) {
            tail = CompletableFuture.completedFuture(null);
        }
        HTTP.newWebSocketBuilder()
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket w) {
                        ws = w;
                        w.request(1);
                        listener.onConnected();
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            String msg = partial.toString();
                            partial.setLength(0);
                            listener.onText(msg);
                        }
                        w.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
                        listener.onClosed("closed " + statusCode + " " + reason);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket w, Throwable error) {
                        listener.onClosed("error " + error);
                    }
                })
                .whenComplete((w, err) -> {
                    if (err != null) {
                        listener.onClosed("cannot connect: " + err);
                    }
                });
    }

    @Override
    public synchronized boolean send(String text) {
        WebSocket w = ws;
        if (w == null) {
            return false;
        }
        tail = tail.thenCompose(x -> w.sendText(text, true)).exceptionally(err -> null);
        return true;
    }

    @Override
    public void close() {
        WebSocket w = ws;
        ws = null;
        if (w != null) {
            w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }
}
