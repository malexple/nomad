package org.nomad.core;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** Ed25519 device authentication using only the JDK (no third-party crypto). */
public final class DeviceAuth {
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private DeviceAuth() {}

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] rawPublicKey(PublicKey key) {
        byte[] enc = key.getEncoded();
        if (enc.length != X509_PREFIX.length + 32) {
            throw new IllegalArgumentException("unexpected Ed25519 key encoding");
        }
        return Arrays.copyOfRange(enc, X509_PREFIX.length, enc.length);
    }

    public static PublicKey publicKeyFromRaw(byte[] raw) {
        if (raw.length != 32) {
            throw new IllegalArgumentException("raw Ed25519 key must be 32 bytes");
        }
        byte[] enc = new byte[X509_PREFIX.length + 32];
        System.arraycopy(X509_PREFIX, 0, enc, 0, X509_PREFIX.length);
        System.arraycopy(raw, 0, enc, X509_PREFIX.length, 32);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(enc));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("bad public key", e);
        }
    }

    /** Canonical string that is signed: METHOD, path+query, unix seconds, SHA-256(body). */
    public static String canonical(String method, String pathAndQuery, long timestamp, byte[] body) {
        return method.toUpperCase(Locale.ROOT) + "\n" + pathAndQuery + "\n" + timestamp + "\n"
                + HexFormat.of().formatHex(Ids.sha256(body));
    }

    public static byte[] sign(PrivateKey key, String canonical) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(PublicKey key, String canonical, byte[] signature) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
