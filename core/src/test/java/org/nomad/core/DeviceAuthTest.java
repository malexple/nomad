package org.nomad.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DeviceAuthTest {
    @Test
    void rfc8032Test1() {
        byte[] seed = Hex.decode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] pub = Hex.decode("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] sig = Hex.decode("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

        DeviceKeyPair kp = DeviceAuth.fromPrivate(seed);
        assertArrayEquals(pub, kp.pub());
        assertArrayEquals(sig, DeviceAuth.signBytes(kp.priv(), new byte[0]));
        assertTrue(DeviceAuth.verifyBytes(pub, new byte[0], sig));
    }

    @Test
    void signVerifyRoundTrip() {
        DeviceKeyPair kp = DeviceAuth.generateKeyPair();
        String canon = DeviceAuth.canonical("POST", "/v1/envelopes", 1_700_000_000L, "body".getBytes());
        byte[] sig = DeviceAuth.sign(kp.priv(), canon);
        assertEquals(64, sig.length);
        assertTrue(DeviceAuth.verify(kp.pub(), canon, sig));
    }

    @Test
    void tamperedDataOrWrongKeyFails() {
        DeviceKeyPair kp = DeviceAuth.generateKeyPair();
        DeviceKeyPair other = DeviceAuth.generateKeyPair();
        String good = DeviceAuth.canonical("POST", "/v1/envelopes", 1L, "a".getBytes());
        String bad = DeviceAuth.canonical("POST", "/v1/envelopes", 1L, "b".getBytes());
        byte[] sig = DeviceAuth.sign(kp.priv(), good);
        assertFalse(DeviceAuth.verify(kp.pub(), bad, sig));
        assertFalse(DeviceAuth.verify(other.pub(), good, sig));
    }

    @Test
    void malformedInputsAreRejectedWithoutExceptions() {
        assertFalse(DeviceAuth.verifyBytes(new byte[5], new byte[0], new byte[64]));
        assertFalse(DeviceAuth.verifyBytes(new byte[32], new byte[0], new byte[10]));
        assertFalse(DeviceAuth.verifyBytes(null, new byte[0], new byte[64]));
    }
}
