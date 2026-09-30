package org.nomad.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Minimal binary writer for persisted state (big endian, length-prefixed byte arrays). */
public final class BinWriter {
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bos);

    public BinWriter i32(int v) {
        try {
            out.writeInt(v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public BinWriter i64(long v) {
        try {
            out.writeLong(v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public BinWriter bool(boolean v) {
        try {
            out.writeBoolean(v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public BinWriter bytes(byte[] b) {
        i32(b.length);
        try {
            out.write(b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public BinWriter nullableBytes(byte[] b) {
        bool(b != null);
        if (b != null) {
            bytes(b);
        }
        return this;
    }

    public BinWriter str(String s) {
        return bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] toByteArray() {
        return bos.toByteArray();
    }
}
