package com.schwab.shortener.url.codec;

/**
 * Strategy for turning a long URL into a short code.
 *
 * <p>Two implementations ship with this service. The hash strategy is stateless but
 * can collide, so it needs the caller to detect a clash and ask again. The counter
 * strategy cannot collide but needs a coordination point. The interface exists so the
 * write path does not care which is in use - see ADR-001 for the comparison and
 * ADR-002 for why the swap between them is the orchestrator's brownfield scenario.
 */
public interface ShortCodeGenerator {

    /**
     * @param longUrl  the canonicalized target URL
     * @param attempt  0 on the first try, incremented when the caller has seen a
     *                 collision and needs a different code for the same URL
     */
    String generate(String longUrl, int attempt);

    /** Whether a generated code can clash with one already stored. */
    boolean canCollide();

    /** Short name used in logs, metrics and the orchestrator audit trail. */
    String getName();
}
