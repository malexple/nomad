package org.nomad.crypto;

/**
 * Pluggable end-to-end encryption session. The scheme version is written into the envelope,
 * so Double Ratchet + Sender Keys can later be replaced by MLS without changing nodes.
 */
public interface E2eeSession {
    int schemeVersion();

    byte[] encrypt(byte[] plaintext);

    byte[] decrypt(byte[] ciphertext);
}
