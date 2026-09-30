package org.nomad.crypto;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HKDF-SHA256 (RFC 5869) on top of the standard HMAC. */
public final class Hkdf {
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    public static byte[] derive(byte[] salt, byte[] ikm, byte[] info, int length) {
        if (length <= 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("bad output length");
        }
        byte[] s = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        byte[] prk = hmac(s, ikm);
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (int counter = 1; pos < length; counter++) {
            t = hmac(prk, t, info, new byte[] {(byte) counter});
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) {
                mac.update(p);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
