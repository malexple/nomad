package org.nomad.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Identifier derivation. uid = base32(SHA-256(identity_pubkey))[0..26] (SPEC section 5). */
public final class Ids {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final byte[] MAILBOX_DOMAIN = "nomad-mbx-v0".getBytes(StandardCharsets.US_ASCII);

    private Ids() {}

    public static String uid(byte[] identityPublicKey) {
        return base32(sha256(identityPublicKey)).substring(0, 26);
    }

    /**
     * v0 stand-in: mailbox id derived from the device public key.
     * Later it becomes a pseudonym derived from the group exporter secret (SPEC section 7.2).
     */
    public static String mailboxIdFor(byte[] devicePublicKey) {
        byte[] input = new byte[MAILBOX_DOMAIN.length + devicePublicKey.length];
        System.arraycopy(MAILBOX_DOMAIN, 0, input, 0, MAILBOX_DOMAIN.length);
        System.arraycopy(devicePublicKey, 0, input, MAILBOX_DOMAIN.length, devicePublicKey.length);
        return HexFormat.of().formatHex(sha256(input));
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }
}
