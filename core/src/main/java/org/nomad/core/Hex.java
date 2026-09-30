package org.nomad.core;

/** Hex without java.util.HexFormat (that class exists on Android only from API 34). */
public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    public static String encode(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[2 * i] = DIGITS[(data[i] >> 4) & 0xf];
            out[2 * i + 1] = DIGITS[data[i] & 0xf];
        }
        return new String(out);
    }

    public static byte[] decode(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("odd length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("not a hex digit");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
