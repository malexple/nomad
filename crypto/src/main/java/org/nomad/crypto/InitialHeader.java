package org.nomad.crypto;

import java.nio.ByteBuffer;

/** Sent by the initiator until the peer answers, so any of the first messages can start the session. */
record InitialHeader(byte[] sigKeyA, byte[] ikDhA, byte[] sigIkDhA, byte[] ekA, int spkId, int opkId) {
    static final int SIZE = 32 + 32 + 64 + 32 + 4 + 4;

    byte[] encode() {
        return ByteBuffer.allocate(SIZE)
                .put(sigKeyA)
                .put(ikDhA)
                .put(sigIkDhA)
                .put(ekA)
                .putInt(spkId)
                .putInt(opkId)
                .array();
    }

    static InitialHeader parse(byte[] buf, int off) {
        if (buf.length - off < SIZE) {
            throw new IllegalArgumentException("short initial header");
        }
        ByteBuffer b = ByteBuffer.wrap(buf, off, SIZE);
        byte[] sigKey = new byte[32];
        byte[] ik = new byte[32];
        byte[] sigIk = new byte[64];
        byte[] ek = new byte[32];
        b.get(sigKey);
        b.get(ik);
        b.get(sigIk);
        b.get(ek);
        return new InitialHeader(sigKey, ik, sigIk, ek, b.getInt(), b.getInt());
    }
}
