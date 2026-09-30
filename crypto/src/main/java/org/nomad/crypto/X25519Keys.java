package org.nomad.crypto;

import java.security.SecureRandom;
import org.bouncycastle.math.ec.rfc7748.X25519;

public final class X25519Keys {
    private static final SecureRandom RANDOM = new SecureRandom();

    private X25519Keys() {}

    public record Pair(byte[] priv, byte[] pub) {}

    public static Pair generate() {
        byte[] k = new byte[32];
        X25519.generatePrivateKey(RANDOM, k);
        return new Pair(k, publicFor(k));
    }

    /** Restores a pair from a stored private key. */
    public static Pair fromPrivate(byte[] priv) {
        if (priv.length != 32) {
            throw new IllegalArgumentException("X25519 private key must be 32 bytes");
        }
        return new Pair(priv.clone(), publicFor(priv));
    }

    public static byte[] publicFor(byte[] priv) {
        byte[] pub = new byte[32];
        X25519.generatePublicKey(priv, 0, pub, 0);
        return pub;
    }

    /** Diffie-Hellman; rejects low-order points (all-zero result). */
    public static byte[] dh(byte[] priv, byte[] pub) {
        if (pub.length != 32) {
            throw new IllegalArgumentException("public key must be 32 bytes");
        }
        byte[] out = new byte[32];
        if (!X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) {
            throw new IllegalArgumentException("invalid X25519 public key");
        }
        return out;
    }
}
