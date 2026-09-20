package com.schwab.shortener;

import com.schwab.shortener.url.codec.CounterBlockAllocator;
import com.schwab.shortener.url.codec.CounterShortCodeGenerator;
import com.schwab.shortener.url.codec.HashShortCodeGenerator;
import com.schwab.shortener.url.codec.InMemoryCounterBlockAllocator;
import com.schwab.shortener.url.codec.ShortCodeGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /** Injected everywhere instead of Instant.now() so tests can control time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The counter needs somewhere to hand out blocks from. In-process here so the app
     * runs with no infrastructure; Redis INCRBY in production - same contract, wider
     * scope. See ADR-001.
     */
    @Bean
    public CounterBlockAllocator counterBlockAllocator() {
        return new InMemoryCounterBlockAllocator();
    }

    /**
     * Picks the generator from configuration. Both live behind one interface, so this
     * is the only line that changes when the strategy changes - which is exactly what
     * makes the hash-to-counter swap a small, reviewable diff.
     */
    @Bean
    public ShortCodeGenerator shortCodeGenerator(
            CounterBlockAllocator allocator,
            @Value("${shortener.strategy:counter}") String strategy,
            @Value("${shortener.hash-salt:local-dev-salt}") String hashSalt,
            @Value("${shortener.code-secret:8675309}") long codeSecret,
            @Value("${shortener.counter-block-size:1000}") int blockSize) {

        if ("hash".equalsIgnoreCase(strategy)) {
            return new HashShortCodeGenerator(hashSalt);
        }
        return new CounterShortCodeGenerator(allocator, codeSecret, blockSize);
    }
}
