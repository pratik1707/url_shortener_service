package com.schwab.shortener.url;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "short_url")
public class ShortUrl {

    /**
     * The short code is the primary key. Lookups on the read path are then a primary
     * key hit rather than a secondary index hit, and uniqueness is enforced by the
     * database rather than by application code.
     */
    @Id
    @Column(name = "short_code", length = 16, nullable = false)
    private String shortCode;

    @Column(name = "long_url", length = 2048, nullable = false)
    private String longUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "custom_alias", nullable = false)
    private boolean customAlias;

    /** Which generator minted this code. Useful when both strategies exist in one table. */
    @Column(name = "strategy", length = 64)
    private String strategy;

    /** How many times this link has been followed. */
    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    protected ShortUrl() {
        // required by JPA
    }

    public ShortUrl(String shortCode, String longUrl, Instant createdAt, Instant expiresAt,
                    String createdBy, boolean customAlias, String strategy) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
        this.customAlias = customAlias;
        this.strategy = strategy;
        this.hitCount = 0L;
    }

    /** Called when the link is followed. */
    public void recordHit(Instant at) {
        this.hitCount++;
        this.lastAccessedAt = at;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public boolean isCustomAlias() {
        return customAlias;
    }

    public String getStrategy() {
        return strategy;
    }

    public long getHitCount() {
        return hitCount;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }
}
