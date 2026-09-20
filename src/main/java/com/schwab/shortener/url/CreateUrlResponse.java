package com.schwab.shortener.url;

import java.time.Instant;

/** Returned by {@code POST /urls}. */
public record CreateUrlResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt,
        String strategy) {
}
