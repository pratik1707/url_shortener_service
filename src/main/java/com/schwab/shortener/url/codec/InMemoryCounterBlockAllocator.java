package com.schwab.shortener.url.codec;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-process allocator backed by an AtomicLong.
 *
 * <p>getAndAdd is atomic, so concurrent callers receive disjoint blocks - the same
 * guarantee Redis INCRBY gives, with the same reasoning, bounded to one JVM. That
 * bound is why this is the prototype default and not the production answer.
 */
public class InMemoryCounterBlockAllocator implements CounterBlockAllocator {

    private final AtomicLong cursor;

    public InMemoryCounterBlockAllocator() {
        this(0L);
    }

    public InMemoryCounterBlockAllocator(long start) {
        this.cursor = new AtomicLong(start);
    }

    @Override
    public long reserveBlock(int blockSize) {
        if (blockSize < 1) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        long start = cursor.getAndAdd(blockSize);
        if (start + blockSize > Base62.DOMAIN) {
            throw new IllegalStateException("counter has exhausted the 6-character code space");
        }
        return start;
    }

    @Override
    public String getName() {
        return "in-memory-atomic";
    }
}
