package org.nomad.core;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a node stores. The node never sees plaintext or the sender.
 * version = encryption scheme version (0 = dev plaintext, NOT for real use).
 * expiryDay = epoch day after which the node may delete the envelope.
 */
public record Envelope(int version, UUID envelopeId, String mailboxId, byte[] ciphertext, long expiryDay) {

    public static final int MAX_CIPHERTEXT_BYTES = 1 << 20;
    private static final Pattern MAILBOX = Pattern.compile("[0-9a-f]{64}");

    public Envelope {
        if (version < 0 || version > 255) {
            throw new IllegalArgumentException("version out of range");
        }
        Objects.requireNonNull(envelopeId, "envelopeId");
        if (mailboxId == null || !MAILBOX.matcher(mailboxId).matches()) {
            throw new IllegalArgumentException("mailboxId must be 64 lowercase hex chars");
        }
        Objects.requireNonNull(ciphertext, "ciphertext");
        if (ciphertext.length == 0 || ciphertext.length > MAX_CIPHERTEXT_BYTES) {
            throw new IllegalArgumentException("ciphertext size out of range");
        }
    }
}
