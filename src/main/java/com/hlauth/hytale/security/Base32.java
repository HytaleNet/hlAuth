package com.hlauth.hytale.security;

/**
 * RFC 4648 Base32 without padding (the encoding used by authenticator apps).
 */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int[] DECODE = new int[128];

    static {
        java.util.Arrays.fill(DECODE, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE[ALPHABET[i]] = i;
            if (ALPHABET[i] >= 'A' && ALPHABET[i] <= 'Z') {
                DECODE[Character.toLowerCase(ALPHABET[i])] = i;
            }
        }
    }

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET[(buffer >> bits) & 31]);
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }

    public static byte[] decode(String text) {
        if (text == null) {
            return new byte[0];
        }
        String compact = text.replace(" ", "").replace("=", "");
        int buffer = 0;
        int bits = 0;
        byte[] tmp = new byte[compact.length()];
        int n = 0;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c >= DECODE.length || DECODE[c] < 0) {
                continue;
            }
            buffer = (buffer << 5) | DECODE[c];
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                tmp[n++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        byte[] out = new byte[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }
}
