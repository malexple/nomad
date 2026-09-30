package org.nomad.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the persisted client state at rest: AES-256-GCM, random 12-byte nonce in front of the ciphertext.
 * The 32-byte master key is supplied by the platform (Android Keystore wrapped key, a passphrase-derived key, ...).
 */
public final class StateVault {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] AAD = Bytes.ascii("nomad-state-v1");
    private static final int NONCE = 12;

    private StateVault() {}

    public static byte[] newKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return k;
    }

    public static byte[] seal(byte[] key, byte[] plaintext) {
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(AAD);
            return Bytes.concat(nonce, c.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] open(byte[] key, byte[] sealed) {
        if (sealed.length < NONCE + 16) {
            throw new DecryptionException("state blob too short");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 0, NONCE)));
            c.updateAAD(AAD);
            return c.doFinal(sealed, NONCE, sealed.length - NONCE);
        } catch (GeneralSecurityException e) {
            throw new DecryptionException("state cannot be decrypted (wrong key or damaged file)", e);
        }
    }
}
