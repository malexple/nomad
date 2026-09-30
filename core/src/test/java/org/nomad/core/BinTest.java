package org.nomad.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BinTest {
    @Test
    void roundTrip() {
        BinWriter w = new BinWriter();
        w.i32(-5).i64(1L << 40).bool(true).bytes(new byte[] {1, 2, 3}).nullableBytes(null).nullableBytes(new byte[] {9})
                .str("привет");
        BinReader r = new BinReader(w.toByteArray());
        assertEquals(-5, r.i32());
        assertEquals(1L << 40, r.i64());
        assertTrue(r.bool());
        assertArrayEquals(new byte[] {1, 2, 3}, r.bytes());
        assertNull(r.nullableBytes());
        assertArrayEquals(new byte[] {9}, r.nullableBytes());
        assertEquals("привет", r.str());
    }

    @Test
    void truncatedAndAbsurdInputIsRejected() {
        byte[] ok = new BinWriter().bytes(new byte[10]).toByteArray();
        byte[] cut = java.util.Arrays.copyOf(ok, ok.length - 3);
        assertThrows(IllegalArgumentException.class, () -> new BinReader(cut).bytes());

        byte[] hugeLength = new BinWriter().i32(Integer.MAX_VALUE).toByteArray();
        assertThrows(IllegalArgumentException.class, () -> new BinReader(hugeLength).bytes());

        byte[] negative = new BinWriter().i32(-1).toByteArray();
        assertThrows(IllegalArgumentException.class, () -> new BinReader(negative).count());
    }
}
