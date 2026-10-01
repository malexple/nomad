package org.nomad.sim;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.nomad.client.ClientState;
import org.nomad.client.NomadEngine;
import org.nomad.client.Persistence;
import org.nomad.core.DeviceAuth;
import org.nomad.core.Ids;
import org.nomad.crypto.GroupManager;

/**
 * One emulated user. All the real work is done by NomadEngine, the same class that runs in the Android app;
 * the simulator only supplies a transport (JdkWebSocketTransport) and counts what arrives.
 */
final class SimUser implements NomadEngine.Listener {
    final String name;
    final String url;
    final CompletableFuture<Void> ready = new CompletableFuture<>();
    final CompletableFuture<Void> published = new CompletableFuture<>();
    final AtomicInteger received = new AtomicInteger();

    private final Stats stats;
    private final ClientState state;
    private final NomadEngine engine;

    /** A new user with fresh keys. */
    SimUser(String name, String url, Stats stats, NomadEngine.Config cfg) {
        this(name, url, stats, cfg, ClientState.fresh(DeviceAuth.generateKeyPair()));
    }

    /** A user restarted from a saved state: same keys, sessions, group chains, read cursor and outbox. */
    SimUser(String name, String url, Stats stats, NomadEngine.Config cfg, ClientState state) {
        this.name = name;
        this.url = url;
        this.stats = stats;
        this.state = state;
        this.engine = new NomadEngine(state, Persistence.NONE, new JdkWebSocketTransport(), url, this, cfg);
    }

    String uid() {
        return state.identity.uid();
    }

    byte[] sigKey() {
        return state.identity.sigPub();
    }

    GroupManager.GroupMember asMember() {
        return new GroupManager.GroupMember(sigKey());
    }

    String mailboxId() {
        return Ids.mailboxIdFor(sigKey());
    }

    void connect() {
        engine.start();
    }

    /** Stops the engine and returns when its thread has finished. */
    void close() {
        engine.shutdown().join();
    }

    byte[] snapshot(byte[] masterKey) throws Exception {
        return engine.snapshot(masterKey).get();
    }

    int heldCount() {
        return engine.heldCount();
    }

    int unacknowledgedSends() {
        return engine.outboxSize();
    }

    int bundleRequests() {
        return engine.bundleRequestCount();
    }

    int rateLimited() {
        return engine.rateLimitedCount();
    }

    String connectionState() {
        return engine.connectionState().name();
    }

    CompletableFuture<Void> sendChat(SimUser to, int index) {
        String text = "sim|" + name + "|" + to.name + "|" + index + "|" + System.currentTimeMillis();
        return failedAsError(engine.sendChat(to.sigKey(), text.getBytes(StandardCharsets.UTF_8)));
    }

    CompletableFuture<String> createGroup(List<SimUser> others) {
        return engine.createGroup(others.stream().map(SimUser::asMember).toList());
    }

    CompletableFuture<Void> sendGroup(String groupId, int index) {
        String text = "sim|" + name + "|G|" + index + "|" + System.currentTimeMillis();
        return failedAsError(engine.sendGroup(groupId, text.getBytes(StandardCharsets.UTF_8)));
    }

    private CompletableFuture<Void> failedAsError(CompletableFuture<Void> f) {
        return f.exceptionally(e -> {
            stats.errors.incrementAndGet();
            System.err.println(name + ": send failed: " + e);
            return null;
        });
    }

    // ------------------------------------------------------------------ engine events

    @Override
    public void onConnectionState(NomadEngine.ConnectionState s) {
        if (s == NomadEngine.ConnectionState.ONLINE) {
            ready.complete(null);
            if (engine.isPrekeysPublished()) {
                published.complete(null);
            }
        }
    }

    @Override
    public void onPrekeysPublished() {
        published.complete(null);
    }

    @Override
    public void onChat(String peerUid, byte[] peerSigKey, byte[] text) {
        deliver(new String(text, StandardCharsets.UTF_8));
    }

    @Override
    public void onGroupMessage(String groupId, String senderUid, byte[] text) {
        deliver(new String(text, StandardCharsets.UTF_8));
    }

    @Override
    public void onSendFailed(String reason) {
        stats.errors.incrementAndGet();
        System.err.println(name + ": " + reason);
    }

    @Override
    public void onError(String message) {
        stats.errors.incrementAndGet();
        System.err.println(name + ": " + message);
    }

    private void deliver(String text) {
        received.incrementAndGet();
        stats.onReceive(text, name);
    }
}
