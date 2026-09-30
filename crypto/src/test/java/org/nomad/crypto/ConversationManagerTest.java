package org.nomad.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.nomad.core.OneTimePrekey;
import org.nomad.core.PrekeyBundle;

class ConversationManagerTest {
    private record Peer(Identity id, ConversationManager mgr) {
        String uid() {
            return id.uid();
        }
    }

    private static Peer peer() {
        Identity i = Identity.generate();
        return new Peer(i, new ConversationManager(i));
    }

    private static PrekeyBundle bundleWithOpk(Identity i) {
        return i.publicBundle().withOpk(i.generateOneTimePrekeys(1).get(0));
    }

    private static byte[] b(String s) {
        return s.getBytes(UTF_8);
    }

    private static String text(Optional<ConversationManager.Decrypted> d) {
        assertTrue(d.isPresent(), "message could not be decrypted");
        return new String(d.get().plaintext(), UTF_8);
    }

    private static void connect(Peer from, Peer to) {
        from.mgr().startSession(to.uid(), bundleWithOpk(to.id()));
    }

    @Test
    void basicExchangeBothDirections() {
        Peer a = peer();
        Peer b = peer();
        connect(a, b);

        byte[] w1 = a.mgr().encryptFor(b.uid(), b("hi"));
        assertEquals(RatchetSession.TYPE_INITIAL, w1[0]);
        assertEquals("hi", text(b.mgr().decrypt(w1)));

        byte[] w2 = b.mgr().encryptFor(a.uid(), b("yo"));
        assertEquals("yo", text(a.mgr().decrypt(w2)));

        byte[] w3 = a.mgr().encryptFor(b.uid(), b("again"));
        assertEquals(RatchetSession.TYPE_NORMAL, w3[0]);
        assertEquals("again", text(b.mgr().decrypt(w3)));
    }

    @Test
    void longAlternatingConversation() {
        Peer a = peer();
        Peer b = peer();
        connect(a, b);
        assertEquals("start", text(b.mgr().decrypt(a.mgr().encryptFor(b.uid(), b("start")))));
        for (int i = 0; i < 50; i++) {
            assertEquals("b" + i, text(a.mgr().decrypt(b.mgr().encryptFor(a.uid(), b("b" + i)))));
            assertEquals("a" + i, text(b.mgr().decrypt(a.mgr().encryptFor(b.uid(), b("a" + i)))));
        }
    }

    @Test
    void outOfOrderAndLateDelivery() {
        Peer a = peer();
        Peer b = peer();
        connect(a, b);
        byte[] m1 = a.mgr().encryptFor(b.uid(), b("m1"));
        byte[] m2 = a.mgr().encryptFor(b.uid(), b("m2"));
        byte[] m3 = a.mgr().encryptFor(b.uid(), b("m3"));
        byte[] m4 = a.mgr().encryptFor(b.uid(), b("m4"));

        assertEquals("m3", text(b.mgr().decrypt(m3)));
        assertEquals("m1", text(b.mgr().decrypt(m1)));
        assertEquals("m4", text(b.mgr().decrypt(m4)));
        assertEquals("m2", text(b.mgr().decrypt(m2)));
    }

    @Test
    void tamperedMessageIsRejectedAndStateSurvives() {
        Peer a = peer();
        Peer b = peer();
        connect(a, b);
        byte[] m1 = a.mgr().encryptFor(b.uid(), b("secret"));
        byte[] bad = m1.clone();
        bad[bad.length - 1] ^= 0x01;

        assertTrue(b.mgr().decrypt(bad).isEmpty());
        assertEquals("secret", text(b.mgr().decrypt(m1)));
    }

    @Test
    void replayIsRejected() {
        Peer a = peer();
        Peer b = peer();
        connect(a, b);
        byte[] m1 = a.mgr().encryptFor(b.uid(), b("once"));
        assertEquals("once", text(b.mgr().decrypt(m1)));
        assertTrue(b.mgr().decrypt(m1).isEmpty());
    }

    @Test
    void bothSidesStartAtTheSameTime() {
        Peer a = peer();
        Peer b = peer();
        connect(a, b);
        connect(b, a);

        byte[] a1 = a.mgr().encryptFor(b.uid(), b("a1"));
        byte[] b1 = b.mgr().encryptFor(a.uid(), b("b1"));
        assertEquals("a1", text(b.mgr().decrypt(a1)));
        assertEquals("b1", text(a.mgr().decrypt(b1)));

        for (int i = 0; i < 10; i++) {
            assertEquals("a" + i, text(b.mgr().decrypt(a.mgr().encryptFor(b.uid(), b("a" + i)))));
            assertEquals("b" + i, text(a.mgr().decrypt(b.mgr().encryptFor(a.uid(), b("b" + i)))));
        }
    }

    @Test
    void oneTimePrekeyIsSingleUse() {
        Peer a = peer();
        Peer b = peer();
        PrekeyBundle bundle = bundleWithOpk(b.id());

        a.mgr().startSession(b.uid(), bundle);
        assertEquals("first", text(b.mgr().decrypt(a.mgr().encryptFor(b.uid(), b("first")))));

        Peer mallory = peer();
        mallory.mgr().startSession(b.uid(), bundle);
        byte[] replayedHandshake = mallory.mgr().encryptFor(b.uid(), b("second"));
        assertTrue(b.mgr().decrypt(replayedHandshake).isEmpty());
    }

    @Test
    void bundleOfSomeoneElseIsRejected() {
        Peer a = peer();
        Peer b = peer();
        Peer c = peer();
        assertThrows(SecurityException.class, () -> a.mgr().startSession(c.uid(), b.id().publicBundle()));
    }

    @Test
    void tamperedBundleIsRejected() {
        Peer a = peer();
        Peer b = peer();
        PrekeyBundle good = b.id().publicBundle();
        byte[] otherSpk = X25519Keys.generate().pub();
        PrekeyBundle bad = new PrekeyBundle(
                good.sigKey(), good.ikDh(), good.sigIkDh(), good.spkId(), otherSpk, good.sigSpk(), null);
        assertFalse(bad.verifySignatures());
        assertThrows(SecurityException.class, () -> a.mgr().startSession(b.uid(), bad));
    }

    @Test
    void tooManySkippedMessagesAreRejected() {
        Peer a = peer();
        Peer b = peer();
        connect(a, b);
        assertEquals("first", text(b.mgr().decrypt(a.mgr().encryptFor(b.uid(), b("first")))));
        assertEquals("reply", text(a.mgr().decrypt(b.mgr().encryptFor(a.uid(), b("reply")))));

        byte[] last = null;
        for (int i = 0; i < DoubleRatchet.MAX_SKIP + 5; i++) {
            last = a.mgr().encryptFor(b.uid(), b("m" + i));
        }
        assertTrue(b.mgr().decrypt(last).isEmpty());
    }

    @Test
    void oneTimePrekeysAreOptional() {
        Peer a = peer();
        Peer b = peer();
        a.mgr().startSession(b.uid(), b.id().publicBundle());
        assertEquals("no opk", text(b.mgr().decrypt(a.mgr().encryptFor(b.uid(), b("no opk")))));
        OneTimePrekey unused = b.id().generateOneTimePrekeys(1).get(0);
        assertEquals(32, unused.pub().length);
    }
}
