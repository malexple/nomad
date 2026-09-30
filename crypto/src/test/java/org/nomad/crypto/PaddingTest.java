package org.nomad.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PaddingTest {
    @Test
    void bucketsAre256_1024_4096_16384_thenMultiplesOf16k() {
        assertEquals(256, Padding.bucket(1));
        assertEquals(256, Padding.bucket(256));
        assertEquals(1024, Padding.bucket(257));
        assertEquals(4096, Padding.bucket(1025));
        assertEquals(16384, Padding.bucket(4097));
        assertEquals(16384, Padding.bucket(16384));
        assertEquals(32768, Padding.bucket(16385));
        assertEquals(49152, Padding.bucket(32769));
    }

    @Test
    void roundTripForManySizesIncludingZerosAndMarkerBytes() {
        for (int n = 0; n <= 600; n++) {
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                data[i] = (byte) (i % 3 == 0 ? 0 : (i % 3 == 1 ? 0x80 : i));
            }
            byte[] padded = Padding.pad(data);
            assertEquals(Padding.bucket(n + 1), padded.length);
            assertArrayEquals(data, Padding.unpad(padded));
        }
    }

    @Test
    void badPaddingIsRejected() {
        assertThrows(DecryptionException.class, () -> Padding.unpad(new byte[10]));
        assertThrows(DecryptionException.class, () -> Padding.unpad(new byte[] {1, 2, 3}));
    }

    @Test
    void messagesOfDifferentLengthShareTheWireLengthWithinABucket() {
        Identity ia = Identity.generate();
        Identity ib = Identity.generate();
        ConversationManager a = new ConversationManager(ia);
        ConversationManager b = new ConversationManager(ib);
        a.startSession(ib.uid(), ib.publicBundle());
        assertTrue(b.decrypt(a.encryptFor(ib.uid(), "hello".getBytes(UTF_8))).isPresent());
        assertTrue(a.decrypt(b.encryptFor(ia.uid(), "hi".getBytes(UTF_8))).isPresent());

        byte[] shortMsg = a.encryptFor(ib.uid(), "x".getBytes(UTF_8));
        byte[] mediumMsg = a.encryptFor(ib.uid(), "y".repeat(200).getBytes(UTF_8));
        byte[] longMsg = a.encryptFor(ib.uid(), "z".repeat(300).getBytes(UTF_8));
        assertEquals(shortMsg.length, mediumMsg.length);
        assertTrue(longMsg.length > mediumMsg.length);

        assertEquals("x", new String(b.decrypt(shortMsg).orElseThrow().plaintext(), UTF_8));
        assertEquals(300, b.decrypt(longMsg).orElseThrow().plaintext().length);
    }
}
