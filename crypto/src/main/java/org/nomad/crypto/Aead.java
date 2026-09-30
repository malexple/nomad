package org.nomad.crypto;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM with a key and nonce derived from a single-use message key. */
final class Aead {
    private Aead() {}

    static byte[] crypt(boolean encrypt, byte[] messageKey, byte[] info, byte[] data, byte[] aad) {
        byte[] okm = Hkdf.derive(new byte[32], messageKey, info, 44);
        SecretKeySpec key = new SecretKeySpec(okm, 0, 32, "AES");
        byte[] nonce = Arrays.copyOfRange(okm, 32, 44);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            if (encrypt) {
                throw new IllegalStateException(e);
            }
            throw new DecryptionException("authentication failed", e);
        }
    }
}
