package org.nomad.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

final class Bytes {
    private Bytes() {}

    static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    static byte[] intBE(int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }

    static int readInt(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).getInt();
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
