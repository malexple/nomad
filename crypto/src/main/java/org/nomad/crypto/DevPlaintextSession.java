package org.nomad.crypto;

/**
 * DEV ONLY. Does NOT encrypt anything (scheme version 0). Exists so the transport and the
 * mailbox can be tested end to end before Double Ratchet is implemented.
 */
public final class DevPlaintextSession implements E2eeSession {
    @Override
    public int schemeVersion() {
        return 0;
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        return plaintext.clone();
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        return ciphertext.clone();
    }
}
