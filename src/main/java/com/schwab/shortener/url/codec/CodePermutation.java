package com.schwab.shortener.url.codec;

/**
 * A keyed, reversible permutation over [0, {@link Base62#DOMAIN}).
 *
 * <p>The counter makes codes unique; this makes them unguessable. A Feistel network is
 * a bijection, so different counter values still give different codes - uniqueness
 * survives the shuffle.
 *
 * <p>62^6 is not a power of two, so the network runs over 2^36 and cycle-walks: if a
 * pass lands outside the domain, run it again. Expected cost is about 1.2 passes.
 *
 * <p>See ADR-001 for why we deviate from the reference design here.
 */
public final class CodePermutation {

    private static final int BITS = 36;
    private static final int HALF = BITS / 2;
    private static final long HALF_MASK = (1L << HALF) - 1;
    private static final long FULL_MASK = (1L << BITS) - 1;
    private static final int ROUNDS = 4;

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private final long[] roundKeys = new long[ROUNDS];

    public CodePermutation(long secret) {
        for (int i = 0; i < ROUNDS; i++) {
            roundKeys[i] = mix(secret + GOLDEN_GAMMA * (i + 1));
        }
    }

    /** Counter value in, code-space value out. */
    public long apply(long id) {
        requireInDomain(id);
        long value = id;
        do {
            value = feistel(value, false);
        } while (value >= Base62.DOMAIN);
        return value;
    }

    /** Code-space value in, counter value out. */
    public long invert(long code) {
        requireInDomain(code);
        long value = code;
        do {
            value = feistel(value, true);
        } while (value >= Base62.DOMAIN);
        return value;
    }

    private long feistel(long input, boolean inverse) {
        long left = (input >>> HALF) & HALF_MASK;
        long right = input & HALF_MASK;
        for (int i = 0; i < ROUNDS; i++) {
            int r = inverse ? ROUNDS - 1 - i : i;
            long next = left ^ (mix(right ^ roundKeys[r]) & HALF_MASK);
            left = right;
            right = next;
        }
        return ((right << HALF) | left) & FULL_MASK;
    }

    /** splitmix64 finalizer - cheap, strong avalanche. */
    private static long mix(long input) {
        long z = input;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void requireInDomain(long value) {
        if (value < 0 || value >= Base62.DOMAIN) {
            throw new IllegalArgumentException("value outside the code space: " + value);
        }
    }
}
