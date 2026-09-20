package com.schwab.shortener.url.codec;

/**
 * Hands out disjoint blocks of counter values.
 *
 * <p>The contract is the important part: two calls never return overlapping blocks,
 * across threads and across instances. Everything else about the counter strategy
 * rests on that.
 *
 * <p>The prototype ships an in-process implementation so the service runs with no
 * infrastructure. Production uses Redis INCRBY, which provides the same guarantee
 * across instances; see ADR-001.
 */
public interface CounterBlockAllocator {

    /**
     * @param blockSize how many consecutive values to reserve
     * @return the first value of the reserved block; the caller owns
     *         [start, start + blockSize)
     */
    long reserveBlock(int blockSize);

    String getName();
}
