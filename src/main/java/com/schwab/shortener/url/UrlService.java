package com.schwab.shortener.url;

import com.schwab.shortener.url.codec.Base62;
import com.schwab.shortener.url.codec.ShortCodeGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Creating and resolving short URLs.
 *
 * <p>This class has no opinion about how codes are made. It asks the generator for one
 * and only checks the database if that generator says it can collide. Swapping hash for
 * counter changes nothing here, which is what makes the migration a small change.
 */
@Service
public class UrlService {

    private static final int MAX_ATTEMPTS = 5;

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator generator;
    private final Clock clock;
    private final String baseUrl;

    public UrlService(ShortUrlRepository repository,
                      ShortCodeGenerator generator,
                      Clock clock,
                      @Value("${shortener.base-url:http://localhost:8080}") String baseUrl) {
        this.repository = repository;
        this.generator = generator;
        this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Transactional
    public CreateUrlResponse create(CreateUrlRequest request) {
        Instant now = clock.instant();
        Instant expiresAt = request.ttlSeconds() == null
                ? null
                : now.plusSeconds(request.ttlSeconds());

        String code;
        boolean custom = request.alias() != null && !request.alias().isBlank();

        if (custom) {
            code = request.alias();
            // A caller asked for a specific string. If it is taken that is a conflict to
            // report, not something to retry around - they wanted that exact code.
            // Aliases are 7-16 characters and generated codes are 6, so this is the only
            // path where a clash is possible at all.
            if (repository.existsById(code)) {
                throw new UrlExceptions.AliasTaken(code);
            }
        } else {
            code = generateUniqueCode(request.url());
        }

        ShortUrl saved = repository.save(new ShortUrl(
                code, request.url(), now, expiresAt,
                request.createdBy(), custom, generator.getName()));

        return new CreateUrlResponse(
                saved.getShortCode(),
                baseUrl + "/" + saved.getShortCode(),
                saved.getLongUrl(),
                saved.getCreatedAt(),
                saved.getExpiresAt(),
                saved.getStrategy());
    }

    /**
     * Asks the generator for a code, checking for a clash only when the strategy
     * says one is possible. Under the counter strategy this is a single call with no
     * database round-trip; under the hash strategy it is a bounded retry loop.
     */
    private String generateUniqueCode(String longUrl) {
        if (!generator.canCollide()) {
            return generator.generate(longUrl, 0);
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = generator.generate(longUrl, attempt);
            if (!repository.existsById(candidate)) {
                return candidate;
            }
        }
        throw new UrlExceptions.GenerationExhausted(MAX_ATTEMPTS);
    }

    /**
     * Resolves a code to its target and counts the hit.
     *
     * <p>Expiry is enforced on read rather than by deleting rows on a timer. A link
     * that has lapsed is gone from the caller's point of view the instant its TTL
     * passes, with no sweeper to fall behind, and the row survives for audit.
     *
     * <p>The hit counter is incremented in the same transaction, which is simple and
     * exact but puts a write on the read path. A popular link would contend on its own
     * row, so at real volume this moves to a buffered or streamed counter - see the
     * trade-offs section of the README. An expired or missing link is not counted.
     */
    @Transactional
    public String resolve(String code) {
        if (!Base62.isValidCode(code) && !looksLikeAlias(code)) {
            throw new UrlExceptions.NotFound(code);
        }
        Optional<ShortUrl> found = repository.findById(code);
        ShortUrl url = found.orElseThrow(() -> new UrlExceptions.NotFound(code));
        Instant now = clock.instant();
        if (url.isExpiredAt(now)) {
            throw new UrlExceptions.Expired(code);
        }
        url.recordHit(now);
        repository.save(url);
        return url.getLongUrl();
    }

    @Transactional(readOnly = true)
    public Optional<ShortUrl> find(String code) {
        return repository.findById(code);
    }

    /** Stats for a link. Works for expired links too - that is part of the answer. */
    @Transactional(readOnly = true)
    public UrlStatsResponse stats(String code) {
        ShortUrl url = repository.findById(code)
                .orElseThrow(() -> new UrlExceptions.NotFound(code));
        return UrlStatsResponse.from(url, url.isExpiredAt(clock.instant()));
    }

    public String getActiveStrategyName() {
        return generator.getName();
    }

    /**
     * Aliases are 7-16 characters, generated codes are exactly 6. The two spaces do not
     * overlap, so a lookup that is not one shape is checked against the other.
     */
    private static boolean looksLikeAlias(String code) {
        return code != null && code.length() >= 7 && code.length() <= 16
                && code.chars().allMatch(Character::isLetterOrDigit);
    }
}
