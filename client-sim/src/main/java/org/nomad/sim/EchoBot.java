package org.nomad.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.nomad.client.ClientState;
import org.nomad.client.FileStateStore;
import org.nomad.client.Invite;
import org.nomad.client.NomadEngine;
import org.nomad.core.DeviceAuth;
import org.nomad.crypto.StateVault;

/**
 * A chat partner on the computer for testing the phone: it keeps a stable identity in a folder, prints its invitation link
 * and answers every direct message with "Эхо: ...". Development tool only: the key of its state file lies next to it.
 *
 * Run:   ./gradlew :client-sim:bot --args="--url ws://localhost:8090/v1/ws --public-url ws://192.168.88.210:8090/v1/ws"
 * Then copy the printed link to the phone (any messenger) and add it as a contact. Optionally "--invite LINK" with the
 * link of the phone makes the bot write first.
 */
public final class EchoBot {
    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        String url = a.getOrDefault("url", "ws://localhost:8090/v1/ws");
        String publicUrl = a.getOrDefault("public-url", url);
        Path dir = Path.of(a.getOrDefault("dir", "bot-data"));
        Files.createDirectories(dir);

        byte[] key = loadOrCreateKey(dir.resolve("master.key"));
        FileStateStore store = new FileStateStore(dir.resolve("state.bin"));
        ClientState loaded = ClientState.load(store, key);
        ClientState state = loaded != null ? loaded : ClientState.fresh(DeviceAuth.generateKeyPair());
        if (loaded == null) {
            state.save(store, key);
        }

        NomadEngine[] holder = new NomadEngine[1];
        NomadEngine.Listener listener = new NomadEngine.Listener() {
            @Override
            public void onConnectionState(NomadEngine.ConnectionState s) {
                System.out.println("connection: " + s);
            }

            @Override
            public void onChat(String peerUid, byte[] peerSigKey, byte[] text) {
                String received = new String(text, StandardCharsets.UTF_8);
                System.out.println("from " + peerUid + ": " + received);
                holder[0].sendChat(peerSigKey, ("Эхо: " + received).getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void onSendFailed(String reason) {
                System.out.println("send failed: " + reason);
            }

            @Override
            public void onError(String message) {
                System.out.println("error: " + message);
            }
        };
        NomadEngine engine = new NomadEngine(
                state, () -> state.save(store, key), new JdkWebSocketTransport(), url, listener, NomadEngine.Config.defaults());
        holder[0] = engine;

        System.out.println("uid: " + engine.uid());
        System.out.println("INVITE: " + Invite.create(engine.sigKey(), "Эхо-бот", publicUrl));
        engine.start();

        if (a.containsKey("invite")) {
            Invite.Data peer = Invite.parse(a.get("invite"));
            engine.sendChat(peer.sigKey(), "Привет! Я эхо-бот.".getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote first to " + peer.name() + " (" + peer.uid() + ")");
        }
        Thread.currentThread().join();
    }

    private static byte[] loadOrCreateKey(Path file) throws IOException {
        if (Files.exists(file)) {
            return Files.readAllBytes(file);
        }
        byte[] key = StateVault.newKey();
        Files.write(file, key);
        return key;
    }
}
