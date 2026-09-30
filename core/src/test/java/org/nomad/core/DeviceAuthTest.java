package org.nomad.core;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import org.junit.jupiter.api.Test;

class DeviceAuthTest {
    @Test
    void signVerifyRoundTrip() {
        KeyPair kp = DeviceAuth.generateKeyPair();
        byte[] raw = DeviceAuth.rawPublicKey(kp.getPublic());
        assertEquals(32, raw.length);

        String canon = DeviceAuth.canonical("POST", "/v1/envelopes", 1_700_000_000L, "body".getBytes());
        byte[] sig = DeviceAuth.sign(kp.getPrivate(), canon);
        assertTrue(DeviceAuth.verify(DeviceAuth.publicKeyFromRaw(raw), canon, sig));
    }

    @Test
    void tamperedBodyFails() {
        KeyPair kp = DeviceAuth.generateKeyPair();
        String good = DeviceAuth.canonical("POST", "/v1/envelopes", 1L, "a".getBytes());
        String bad = DeviceAuth.canonical("POST", "/v1/envelopes", 1L, "b".getBytes());
        byte[] sig = DeviceAuth.sign(kp.getPrivate(), good);
        assertFalse(DeviceAuth.verify(kp.getPublic(), bad, sig));
    }
}
