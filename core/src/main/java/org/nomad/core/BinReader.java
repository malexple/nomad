package org.nomad.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Reader for BinWriter output. Every problem (truncated or absurd data) is an IllegalArgumentException. */
public final class BinReader {
    private static final int MAX_COUNT = 1_000_000;

    private final DataInputStream in;

    public BinReader(byte[] data) {
        this.in = new DataInputStream(new ByteArrayInputStream(data));
    }

    public int i32() {
        try {
            return in.readInt();
        } catch (IOException e) {
            throw new IllegalArgumentException("corrupt state: truncated", e);
        }
    }

    public long i64() {
        try {
            return in.readLong();
        } catch (IOException e) {
            throw new IllegalArgumentException("corrupt state: truncated", e);
        }
    }

    public boolean bool() {
        try {
            return in.readBoolean();
        } catch (IOException e) {
            throw new IllegalArgumentException("corrupt state: truncated", e);
        }
    }

    public byte[] bytes() {
        int n = i32();
        try {
            if (n < 0 || n > in.available()) {
                throw new IllegalArgumentException("corrupt state: bad length");
            }
            byte[] b = new byte[n];
            in.readFully(b);
            return b;
        } catch (IOException e) {
            throw new IllegalArgumentException("corrupt state: truncated", e);
        }
    }

    public byte[] nullableBytes() {
        return bool() ? bytes() : null;
    }

    public String str() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    /** A collection size; rejects negative and absurd values. */
    public int count() {
        int n = i32();
        if (n < 0 || n > MAX_COUNT) {
            throw new IllegalArgumentException("corrupt state: bad count");
        }
        return n;
    }
}
