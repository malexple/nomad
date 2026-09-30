package org.nomad.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StateVaultTest {
    @Test
    void roundTrip() {
        byte[] key = StateVault.newKey();
        byte[] data = "secret state".getBytes();
        byte[] sealed = StateVault.seal(key, data);
        assertArrayEquals(data, StateVault.open(key, sealed));
    }

    @Test
    void sameDataGivesDifferentCiphertexts() {
        byte[] key = StateVault.newKey();
        byte[] data = new byte[100];
        assertFalse(java.util.Arrays.equals(StateVault.seal(key, data), StateVault.seal(key, data)));
    }

    @Test
    void wrongKeyTamperingAndTruncationAreRejected() {
        byte[] key = StateVault.newKey();
        byte[] sealed = StateVault.seal(key, new byte[50]);

        assertThrows(DecryptionException.class, () -> StateVault.open(StateVault.newKey(), sealed));

        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(DecryptionException.class, () -> StateVault.open(key, tampered));

        assertThrows(DecryptionException.class, () -> StateVault.open(key, new byte[10]));
    }
}
