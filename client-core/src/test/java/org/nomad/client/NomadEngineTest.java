package org.nomad.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.nomad.core.DeviceAuth;
import org.nomad.core.DeviceKeyPair;
import org.nomad.core.Ids;
import org.nomad.core.PrekeyBundle;
import org.nomad.crypto.ConversationManager;
import org.nomad.crypto.Identity;
import org.nomad.crypto.StateVault;

class NomadEngineTest {
    private static final ObjectMapper M = new ObjectMapper();

    private final List<Rig> rigs = new ArrayList<>();

    @AfterEach
    void shutDown() {
        rigs.forEach(r -> r.engine.shutdown());
    }

    /** Fake socket: remembers what the engine sends, lets the test play the node. */
    private static final class FakeTransport implements Transport {
        final List<String> events;
        final List<String> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger connects = new AtomicInteger();
        volatile TransportListener listener;

        FakeTransport(List<String> events) {
            this.events = events;
        }

        @Override
        public void connect(String url, TransportListener l) {
            listener = l;
            connects.incrementAndGet();
            l.onConnected();
        }

        @Override
        public boolean send(String text) {
            events.add("send:" + type(text));
            sent.add(text);
            return true;
        }

        @Override
        public void close() {}

        void serverSays(String json) {
            listener.onText(json);
        }

        List<JsonNode> framesOfType(String type) {
            List<JsonNode> out = new ArrayList<>();
            for (String s : sent) {
                if (type.equals(type(s))) {
                    try {
                        out.add(M.readTree(s));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return out;
        }

        static String type(String json) {
            try {
                return M.readTree(json).path("t").asText();
            } catch (Exception e) {
                return "?";
            }
        }
    }

    private static final class Rig {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final FakeTransport transport = new FakeTransport(events);
        final AtomicInteger saves = new AtomicInteger();
        final List<String> chats = new CopyOnWriteArrayList<>();
        final List<String> failures = new CopyOnWriteArrayList<>();
        final ClientState state;
        final NomadEngine engine;

        Rig(ClientState state) {
            this.state = state;
            this.engine = new NomadEngine(
                    state,
                    () -> {
                        saves.incrementAndGet();
                        events.add("save");
                    },
                    transport,
                    "ws://test",
                    new NomadEngine.Listener() {
                        @Override
                        public void onChat(String peerUid, byte[] peerSigKey, byte[] text) {
                            chats.add(peerUid + ":" + new String(text, StandardCharsets.UTF_8));
                        }

                        @Override
                        public void onSendFailed(String reason) {
                            failures.add(reason);
                        }
                    },
                    new NomadEngine.Config(0, 0, 0, 20, 100));
        }

        /** Connects, answers the challenge and the authentication, waits for the prekey upload and the first sync. */
        void goOnline(String nonce) throws Exception {
            if (transport.connects.get() == 0) {
                engine.start();
                awaitTrue(() -> transport.connects.get() >= 1, "connect");
            }
            int authsBefore = transport.framesOfType("auth").size();
            transport.serverSays("{\"t\":\"challenge\",\"nonce\":\"" + nonce + "\"}");
            awaitTrue(() -> transport.framesOfType("auth").size() > authsBefore, "auth frame");
            int syncsBefore = transport.framesOfType("sync").size();
            transport.serverSays("{\"t\":\"auth_ok\",\"mailbox\":\"x\",\"epoch\":1}");
            awaitTrue(() -> transport.framesOfType("sync").size() > syncsBefore, "sync after auth_ok");
        }
    }

    private Rig rig(ClientState state) {
        Rig r = new Rig(state);
        rigs.add(r);
        return r;
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                fail("timeout waiting for: " + what);
            }
            Thread.sleep(5);
        }
    }

    private static String pkReply(Identity peer, PrekeyBundle bundle) throws Exception {
        JsonNode put = M.readTree(WsProtocol.pkPut(bundle, List.of()));
        ObjectNode reply = M.createObjectNode();
        reply.put("t", "pk");
        reply.put("uid", peer.uid());
        reply.set("bundle", put.path("bundle"));
        return reply.toString();
    }

    private static int indexOfLast(List<String> events, String what) {
        return events.lastIndexOf(what);
    }

    @Test
    void authenticatesAndUploadsPrekeysAfterSaving() throws Exception {
        Rig r = rig(ClientState.fresh(DeviceAuth.generateKeyPair()));
        r.goOnline("00ff");

        JsonNode auth = r.transport.framesOfType("auth").get(0);
        byte[] pub = Base64.getDecoder().decode(auth.path("key").asText());
        byte[] sig = Base64.getDecoder().decode(auth.path("sig").asText());
        assertArrayEquals(r.engine.sigKey(), pub);
        assertTrue(DeviceAuth.verify(pub, "NOMAD-WS-AUTH\n00ff", sig));

        awaitTrue(() -> !r.transport.framesOfType("pk_put").isEmpty(), "pk_put");
        int firstPk = r.events.indexOf("send:pk_put");
        assertTrue(r.events.subList(0, firstPk).contains("save"), "the new one-time prekeys must be saved before they are published");

        r.transport.serverSays("{\"t\":\"pk_ok\",\"opks\":20}");
        awaitTrue(() -> r.engine.isPrekeysPublished(), "published flag");
    }

    @Test
    void chatNeedsTheKeysThenSavesBeforeSendingAndIsDecryptableByThePeer() throws Exception {
        Rig r = rig(ClientState.fresh(DeviceAuth.generateKeyPair()));
        r.goOnline("aa");
        Identity peer = Identity.generate();
        ConversationManager peerConvo = new ConversationManager(peer);
        PrekeyBundle bundle = peer.publicBundle().withOpk(peer.generateOneTimePrekeys(1).get(0));

        CompletableFuture<Void> done = r.engine.sendChat(peer.sigPub(), "hi".getBytes(StandardCharsets.UTF_8));
        awaitTrue(() -> !r.transport.framesOfType("pk_get").isEmpty(), "pk_get");
        assertEquals(peer.uid(), r.transport.framesOfType("pk_get").get(0).path("uid").asText());
        assertFalse(done.isDone());

        r.transport.serverSays(pkReply(peer, bundle));
        awaitTrue(() -> !r.transport.framesOfType("send").isEmpty(), "send frame");
        done.get(2, TimeUnit.SECONDS);

        assertTrue(indexOfLast(r.events, "save") < r.events.indexOf("send:send"),
                "the state must be saved before the ciphertext is sent");
        assertEquals(1, r.engine.outboxSize());

        JsonNode frame = r.transport.framesOfType("send").get(0);
        assertEquals(Ids.mailboxIdFor(peer.sigPub()), frame.path("mailbox").asText());
        byte[] wire = Base64.getDecoder().decode(frame.path("ct").asText());
        var decrypted = peerConvo.decrypt(wire).orElseThrow();
        assertEquals(r.engine.uid(), decrypted.peerUid());
        assertEquals(0x01, decrypted.plaintext()[0]);
        assertEquals("hi", new String(decrypted.plaintext(), 1, decrypted.plaintext().length - 1, StandardCharsets.UTF_8));

        r.transport.serverSays("{\"t\":\"ack\",\"id\":\"" + frame.path("id").asText() + "\",\"seq\":1,\"dup\":false}");
        awaitTrue(() -> r.engine.outboxSize() == 0, "outbox emptied by the acknowledgement");
    }

    @Test
    void unacknowledgedEnvelopesAreResentAfterReconnectAndAfterARestart() throws Exception {
        Rig r = rig(ClientState.fresh(DeviceAuth.generateKeyPair()));
        r.goOnline("bb");
        Identity peer = Identity.generate();
        PrekeyBundle bundle = peer.publicBundle().withOpk(peer.generateOneTimePrekeys(1).get(0));
        r.engine.sendChat(peer.sigPub(), "again".getBytes(StandardCharsets.UTF_8));
        awaitTrue(() -> !r.transport.framesOfType("pk_get").isEmpty(), "pk_get");
        r.transport.serverSays(pkReply(peer, bundle));
        awaitTrue(() -> !r.transport.framesOfType("send").isEmpty(), "first send");
        String id = r.transport.framesOfType("send").get(0).path("id").asText();

        // the connection breaks: the same envelope is sent again after the automatic reconnect
        r.transport.listener.onClosed("lost");
        awaitTrue(() -> r.transport.connects.get() >= 2, "automatic reconnect");
        r.goOnline("cc");
        awaitTrue(() -> r.transport.framesOfType("send").size() >= 2, "resend after reconnect");
        assertEquals(id, r.transport.framesOfType("send").get(1).path("id").asText());

        // the device is killed: a new engine from the sealed state still has the envelope
        byte[] key = StateVault.newKey();
        byte[] sealed = r.engine.snapshot(key).get(2, TimeUnit.SECONDS);
        Rig again = rig(ClientState.open(key, sealed));
        assertEquals(1, again.engine.outboxSize());
        again.goOnline("dd");
        awaitTrue(() -> !again.transport.framesOfType("send").isEmpty(), "resend after restart");
        assertEquals(id, again.transport.framesOfType("send").get(0).path("id").asText());
    }

    @Test
    void messagesAreAcknowledgedOnlyAfterSavingAndDuplicatesAreIgnored() throws Exception {
        Rig r = rig(ClientState.fresh(DeviceAuth.generateKeyPair()));
        r.goOnline("ee");

        Identity me = r.state.identity;
        Identity peer = Identity.generate();
        ConversationManager peerConvo = new ConversationManager(peer);
        peerConvo.startSession(me.uid(), me.publicBundle().withOpk(me.generateOneTimePrekeys(1).get(0)));
        byte[] plain = new byte[] {0x01, 'y', 'o'};
        byte[] wire = peerConvo.encryptFor(me.uid(), plain);

        String id = UUID.randomUUID().toString();
        String frame = "{\"t\":\"msgs\",\"epoch\":1,\"more\":false,\"items\":[{\"seq\":7,\"id\":\"" + id
                + "\",\"v\":1,\"ct\":\"" + Base64.getEncoder().encodeToString(wire) + "\",\"exp\":1}]}";
        int eventsBefore = r.events.size();
        r.transport.serverSays(frame);
        awaitTrue(() -> r.chats.size() == 1, "chat callback");
        assertEquals(peer.uid() + ":yo", r.chats.get(0));
        awaitTrue(() -> !r.transport.framesOfType("ack").isEmpty(), "ack frame");
        assertEquals(7, r.transport.framesOfType("ack").get(0).path("upTo").asLong());
        int ackIndex = r.events.indexOf("send:ack");
        assertTrue(r.events.subList(eventsBefore, ackIndex).contains("save"),
                "the state must be saved after the message was processed and before the node is told it may delete it");

        r.transport.serverSays(frame);
        Thread.sleep(100);
        assertEquals(1, r.chats.size(), "the same envelope must not be delivered twice");
    }

    @Test
    void anUnknownRecipientFailsTheSendInsteadOfWaitingForever() throws Exception {
        Rig r = rig(ClientState.fresh(DeviceAuth.generateKeyPair()));
        r.goOnline("ff");
        DeviceKeyPair stranger = DeviceAuth.generateKeyPair();
        CompletableFuture<Void> done = r.engine.sendChat(stranger.pub(), new byte[] {1});
        awaitTrue(() -> !r.transport.framesOfType("pk_get").isEmpty(), "pk_get");
        r.transport.serverSays("{\"t\":\"pk_none\",\"uid\":\"" + stranger.uid() + "\"}");
        awaitTrue(done::isCompletedExceptionally, "the send fails");
        assertFalse(r.failures.isEmpty());
    }
}
