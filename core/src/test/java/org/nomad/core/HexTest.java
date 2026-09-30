package org.nomad.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HexTest {
    @Test
    void roundTripAndKnownValue() {
        byte[] data = {0, 1, (byte) 0x7f, (byte) 0x80, (byte) 0xff};
        assertEquals("00017f80ff", Hex.encode(data));
        assertArrayEquals(data, Hex.decode("00017f80ff"));
        assertArrayEquals(data, Hex.decode("00017F80FF"));
    }

    @Test
    void badInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("abc"));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("zz"));
    }
}
