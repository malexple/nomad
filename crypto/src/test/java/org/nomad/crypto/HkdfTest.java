package org.nomad.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HkdfTest {
    @Test
    void rfc5869TestCase1() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
        byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = Hkdf.derive(salt, ikm, info, 42);
        assertEquals(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                HexFormat.of().formatHex(okm));
    }
}
