package com.schwab.shortener.url.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortCodeGeneratorTest {

    // ---------------------------------------------------------------- hash strategy

    @Test
    @DisplayName("hash: the same url and attempt always give the same code")
    void hashIsDeterministic() {
        HashShortCodeGenerator generator = new HashShortCodeGenerator("pepper");
        assertEquals(generator.generate("https://example.com/a", 0),
                     generator.generate("https://example.com/a", 0));
    }

    @Test
    @DisplayName("hash: retrying gives a different code for the same url")
    void hashRetryGivesADifferentCode() {
        HashShortCodeGenerator generator = new HashShortCodeGenerator("pepper");
        assertNotEquals(generator.generate("https://example.com/a", 0),
                        generator.generate("https://example.com/a", 1));
    }

    @Test
    @DisplayName("hash: admits it can collide, so callers know to check")
    void hashAdmitsItCanCollide() {
        assertTrue(new HashShortCodeGenerator("pepper").canCollide());
    }

    // ------------------------------------------------------------- counter strategy

    @Test
    @DisplayName("counter: ten thousand codes with no repeats")
    void counterProducesNoRepeats() {
        CounterShortCodeGenerator generator = newCounterGenerator();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(codes.add(generator.generate("https://example.com", 0)));
        }
    }

    @Test
    @DisplayName("counter: a code can be turned back into the id that made it")
    void counterCodesDecodeBackToTheirId() {
        CounterShortCodeGenerator generator = newCounterGenerator();
        String first = generator.generate("https://example.com", 0);
        assertEquals(0L, generator.decodeToCounterValue(first));
    }

    @Test
    @DisplayName("counter: promises it cannot collide, so the write path skips the lookup")
    void counterPromisesNoCollisions() {
        assertFalse(newCounterGenerator().canCollide());
    }

    /**
     * The claim behind the counter design is that concurrent writers never get the same
     * value. Asserting it in prose is easy; this is the test that actually holds it.
     */
    @Test
    @DisplayName("counter: sixteen threads generating at once produce no duplicates")
    void concurrentGenerationProducesNoDuplicates() throws Exception {
        int threads = 16;
        int perThread = 2_000;
        CounterShortCodeGenerator generator = newCounterGenerator();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                startTogether.await();
                List<String> codes = new ArrayList<>(perThread);
                for (int i = 0; i < perThread; i++) {
                    codes.add(generator.generate("https://example.com", 0));
                }
                return codes;
            }));
        }
        startTogether.countDown();

        Set<String> all = new HashSet<>();
        int produced = 0;
        for (Future<List<String>> future : futures) {
            List<String> codes = future.get(30, TimeUnit.SECONDS);
            produced += codes.size();
            all.addAll(codes);
        }
        pool.shutdown();

        assertEquals(threads * perThread, produced);
        assertEquals(produced, all.size(), "some codes were handed out twice");
    }

    @Test
    @DisplayName("allocator: concurrent callers never get overlapping blocks")
    void allocatorBlocksNeverOverlap() throws Exception {
        InMemoryCounterBlockAllocator allocator = new InMemoryCounterBlockAllocator();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            futures.add(pool.submit(() -> allocator.reserveBlock(100)));
        }
        Set<Long> starts = new HashSet<>();
        for (Future<Long> future : futures) {
            starts.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1000, starts.size());
    }

    private static CounterShortCodeGenerator newCounterGenerator() {
        return new CounterShortCodeGenerator(new InMemoryCounterBlockAllocator(), 0xC0FFEEL, 500);
    }
}
