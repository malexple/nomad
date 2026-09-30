package org.nomad.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.nomad.client.CursorState;
import org.nomad.client.HttpPort;
import org.nomad.client.NomadClient;
import org.nomad.client.Received;
import org.nomad.core.DeviceAuth;
import org.nomad.crypto.DevPlaintextSession;
import org.nomad.crypto.E2eeSession;

/**
 * Commands: keygen | whoami | send MAILBOX_ID TEXT... | recv.
 * Env: NOMAD_URL (default http://localhost:8080), NOMAD_KEY (default device.key), NOMAD_CURSOR (default cursor.txt).
 * WARNING: v0 uses DevPlaintextSession, nothing is encrypted yet.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: keygen | whoami | send MAILBOX_ID TEXT... | recv");
            System.exit(2);
        }
        Path keyFile = Path.of(env("NOMAD_KEY", "device.key"));
        if (args[0].equals("keygen")) {
            saveKey(keyFile, DeviceAuth.generateKeyPair());
            System.out.println("key written to " + keyFile);
            return;
        }
        KeyPair kp = loadKey(keyFile);
        NomadClient client = new NomadClient(new JdkHttp(env("NOMAD_URL", "http://localhost:8080")), kp);
        E2eeSession session = new DevPlaintextSession();
        switch (args[0]) {
            case "whoami" -> {
                System.out.println("uid:     " + client.uid());
                System.out.println("mailbox: " + client.mailboxId());
            }
            case "send" -> {
                if (args.length < 3) {
                    System.err.println("send MAILBOX_ID TEXT...");
                    System.exit(2);
                }
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                byte[] ct = session.encrypt(text.getBytes(StandardCharsets.UTF_8));
                System.out.println("sent, seq=" + client.send(args[1], session.schemeVersion(), ct));
            }
            case "recv" -> {
                Path cf = Path.of(env("NOMAD_CURSOR", "cursor.txt"));
                CursorState cursor = loadCursor(cf);
                List<Received> got = client.poll(cursor, 100);
                for (Received r : got) {
                    System.out.println("#" + r.seq() + " " + new String(session.decrypt(r.payload()), StandardCharsets.UTF_8));
                }
                if (!got.isEmpty()) {
                    client.ack(cursor);
                }
                Files.writeString(cf, cursor.epoch() + "\n" + cursor.seq() + "\n");
                System.out.println(got.size() + " new message(s)");
            }
            default -> {
                System.err.println("unknown command");
                System.exit(2);
            }
        }
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private static void saveKey(Path f, KeyPair kp) throws IOException {
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pub = Base64.getEncoder().encodeToString(DeviceAuth.rawPublicKey(kp.getPublic()));
        Files.writeString(f, priv + "\n" + pub + "\n");
    }

    private static KeyPair loadKey(Path f) throws IOException, GeneralSecurityException {
        List<String> lines = Files.readAllLines(f);
        PrivateKey priv = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines.get(0))));
        return new KeyPair(DeviceAuth.publicKeyFromRaw(Base64.getDecoder().decode(lines.get(1))), priv);
    }

    private static CursorState loadCursor(Path f) throws IOException {
        if (!Files.exists(f)) {
            return new CursorState();
        }
        List<String> l = Files.readAllLines(f);
        return new CursorState(Long.parseLong(l.get(0)), Long.parseLong(l.get(1)));
    }

    private record JdkHttp(String base) implements HttpPort {
        private static final HttpClient CLIENT = HttpClient.newHttpClient();

        @Override
        public Response execute(String method, String pathAndQuery, Map<String, String> headers, byte[] body)
                throws IOException {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                    .method(method, body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(b::header);
            try {
                HttpResponse<byte[]> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                return new Response(r.statusCode(), r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }
}
