package org.nomad.crypto;

import java.util.Arrays;

/**
 * Hides the message length: the plaintext is followed by 0x80 and zeros up to a size bucket
 * (256, 1024, 4096, 16384 bytes, then multiples of 16384). The node sees only the bucket, not the length.
 */
final class Padding {
    private static final int[] BUCKETS = {256, 1024, 4096, 16384};
    private static final int STEP = 16384;

    private Padding() {}

    static int bucket(int n) {
        for (int b : BUCKETS) {
            if (n <= b) {
                return b;
            }
        }
        return ((n + STEP - 1) / STEP) * STEP;
    }

    static byte[] pad(byte[] data) {
        byte[] out = new byte[bucket(data.length + 1)];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) 0x80;
        return out;
    }

    static byte[] unpad(byte[] padded) {
        int i = padded.length - 1;
        while (i >= 0 && padded[i] == 0) {
            i--;
        }
        if (i < 0 || padded[i] != (byte) 0x80) {
            throw new DecryptionException("bad padding");
        }
        return Arrays.copyOf(padded, i);
    }
}
