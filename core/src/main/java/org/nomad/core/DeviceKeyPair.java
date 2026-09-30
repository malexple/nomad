package org.nomad.core;

/** Ed25519 key pair as raw bytes: priv = 32-byte seed, pub = 32-byte public key. */
public record DeviceKeyPair(byte[] priv, byte[] pub) {
    public DeviceKeyPair {
        if (priv == null || priv.length != 32 || pub == null || pub.length != 32) {
            throw new IllegalArgumentException("Ed25519 keys must be 32 bytes");
        }
    }

    public String uid() {
        return Ids.uid(pub);
    }
}
