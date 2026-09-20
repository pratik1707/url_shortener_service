package com.schwab.shortener.url.codec;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The default strategy: counter, shuffled, then base62-encoded.
 *
 * <pre>
 *   id    = counter value        // unique by construction
 *   mixed = permutation(id)      // reversible, hides the order
 *   code  = Base62.encode(mixed) // always 6 characters
 * </pre>
 *
 * <p>Collisions are impossible, so the write path never has to read first to check a
 * code is free. The permutation stops sequential ids becoming sequential codes.
 *
 * <p>Ids come from locally held blocks, so the allocator is called once per block
 * rather than once per write. A block lost to a crash leaves a gap, which is fine -
 * the counter guarantees uniqueness, not density.
 */
public class CounterShortCodeGenerator implements ShortCodeGenerator {

    private final CounterBlockAllocator allocator;
    private final CodePermutation permutation;
    private final int blockSize;

    private final ReentrantLock lock = new ReentrantLock();
    private long nextValue;
    private long blockEnd;

    public CounterShortCodeGenerator(CounterBlockAllocator allocator, long secret, int blockSize) {
        if (blockSize < 1) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        this.allocator = allocator;
        this.permutation = new CodePermutation(secret);
        this.blockSize = blockSize;
        this.nextValue = 0L;
        this.blockEnd = 0L;
    }

    @Override
    public String generate(String longUrl, int attempt) {
        return Base62.encode(permutation.apply(nextCounterValue()));
    }

    /** Decodes a code back to the counter value that produced it. */
    public long decodeToCounterValue(String code) {
        return permutation.invert(Base62.decode(code));
    }

    private long nextCounterValue() {
        lock.lock();
        try {
            if (nextValue >= blockEnd) {
                long start = allocator.reserveBlock(blockSize);
                nextValue = start;
                blockEnd = start + blockSize;
            }
            return nextValue++;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean canCollide() {
        return false;
    }

    @Override
    public String getName() {
        return "counter-base62-permuted";
    }
}
