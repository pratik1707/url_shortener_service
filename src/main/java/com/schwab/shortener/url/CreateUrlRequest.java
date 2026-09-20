package com.schwab.shortener.url;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /urls}.
 *
 * @param url        the destination; must be http or https
 * @param alias      optional caller-chosen code. Minimum length is 7 on purpose:
 *                   generated codes are always exactly 6 characters, so an alias can
 *                   never collide with a code the counter hands out later - which is
 *                   what lets the write path skip the "is this code free" lookup.
 * @param ttlSeconds optional time to live in seconds; absent means the link does not expire
 * @param createdBy  optional free-text owner, stored for audit
 */
public record CreateUrlRequest(
        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        @Pattern(regexp = "^https?://.+", message = "url must start with http:// or https://")
        String url,

        @Pattern(regexp = "^[0-9A-Za-z]{7,16}$", message = "alias must be 7-16 alphanumeric characters")
        String alias,

        @Positive(message = "ttlSeconds must be positive")
        Long ttlSeconds,

        String createdBy) {
}
