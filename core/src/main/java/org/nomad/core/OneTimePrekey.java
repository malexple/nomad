package org.nomad.core;

import java.util.Objects;

public record OneTimePrekey(int id, byte[] pub) {
    public OneTimePrekey {
        Objects.requireNonNull(pub, "pub");
        if (pub.length != 32) {
            throw new IllegalArgumentException("one-time prekey must be 32 bytes");
        }
    }
}
