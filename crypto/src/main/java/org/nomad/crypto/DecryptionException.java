package org.nomad.crypto;

/** A message could not be decrypted or authenticated. The session state is NOT changed. */
public final class DecryptionException extends RuntimeException {
    public DecryptionException(String message) {
        super(message);
    }

    public DecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
