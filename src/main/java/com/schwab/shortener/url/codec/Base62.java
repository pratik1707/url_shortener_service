package com.schwab.shortener.url.codec;

/**
 * Fixed-width base62 codec.
 *
 * Codes are always exactly {@link #CODE_LENGTH} characters. A fixed width keeps
 * the URL space uniform and means a code can be validated by shape before any
 * datastore is touched.
 */
public final class Base62 {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = 62;

    public static final int CODE_LENGTH = 6;

    /** 62^6 - the exact size of the six-character code space. */
    public static final long DOMAIN = 56_800_235_584L;

    private Base62() {
    }

    public static String encode(long value) {
        if (value < 0 || value >= DOMAIN) {
            throw new IllegalArgumentException("value outside the 6-character code space: " + value);
        }
        char[] out = new char[CODE_LENGTH];
        long remaining = value;
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (remaining % BASE));
            remaining /= BASE;
        }
        return new String(out);
    }

    public static long decode(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("code must be exactly " + CODE_LENGTH + " characters");
        }
        long value = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = ALPHABET.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("illegal character in code: " + code.charAt(i));
            }
            value = value * BASE + digit;
        }
        return value;
    }

    public static boolean isValidCode(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
