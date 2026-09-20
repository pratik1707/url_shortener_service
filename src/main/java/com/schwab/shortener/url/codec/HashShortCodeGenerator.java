package com.schwab.shortener.url.codec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The original strategy: SHA-256 of the URL, folded into the code space. Kept as an
 * alternative behind {@link ShortCodeGenerator}; see ADR-001.
 *
 * <p>Stateless, so any instance can produce a code without coordinating with anything.
 * The cost is that two URLs can land on the same code - roughly n / 62^6 for the next
 * write with n codes stored - so the caller has to check and retry.
 */
public class HashShortCodeGenerator implements ShortCodeGenerator {

    private final String salt;

    public HashShortCodeGenerator(String salt) {
        this.salt = salt == null ? "" : salt;
    }

    @Override
    public String generate(String longUrl, int attempt) {
        byte[] digest = sha256(salt + "|" + attempt + "|" + longUrl);

        // Fold the leading 8 bytes into a long, clear the sign bit, then reduce into
        // the code space. Modulo bias across 2^63 into 62^6 is far below the
        // collision probability the strategy already accepts.
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        value &= Long.MAX_VALUE;
        return Base62.encode(value % Base62.DOMAIN);
    }

    @Override
    public boolean canCollide() {
        return true;
    }

    @Override
    public String getName() {
        return "hash-sha256";
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
