package com.schwab.shortener.url;

import java.time.Instant;

/** What we can say about a link without storing anything about who followed it. */
public record UrlStatsResponse(
        String shortCode,
        String longUrl,
        long hitCount,
        Instant createdAt,
        Instant expiresAt,
        Instant lastAccessedAt,
        boolean expired,
        boolean customAlias,
        String strategy) {

    /** Builds the response from the entity, so the entity itself never leaves the service. */
    public static UrlStatsResponse from(ShortUrl url, boolean expired) {
        return new UrlStatsResponse(
                url.getShortCode(),
                url.getLongUrl(),
                url.getHitCount(),
                url.getCreatedAt(),
                url.getExpiresAt(),
                url.getLastAccessedAt(),
                expired,
                url.isCustomAlias(),
                url.getStrategy());
    }
}
