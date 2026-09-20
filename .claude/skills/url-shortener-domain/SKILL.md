---
name: url-shortener-domain
description: Rules and API contract for the URL shortener - aliases, TTL, redirects, stats and short-code generation. Use when changing anything under url/ or url/codec/, or when a request mentions aliases, expiry, TTL, redirects or click counts.
---
# URL shortener domain rules

## API contract

| Endpoint | Request | Responses |
|---|---|---|
| `POST /urls` | `{ "url", "alias"?, "ttlSeconds"?, "createdBy"? }` | `201` + `CreateUrlResponse`, `400` invalid input, `409` alias taken |
| `GET /{code}` | - | `302` with `Location` and `Cache-Control: no-store`, `404` unknown, `410` expired |
| `GET /urls/{code}/stats` | - | `200` + `UrlStatsResponse`, `404` unknown |

Changing any status code or field name is a contract change: say so in the design and
update README.md and the end-to-end tests in the same change.

## Aliases

- 7 to 16 characters, `[0-9A-Za-z]` only. Validated on `CreateUrlRequest`.
- Generated codes are always exactly 6, so aliases and generated codes can never collide.
  That is why the counter path does not check whether a code is free. Never allow a
  6-character alias.
- A taken alias returns `409 Conflict`. Never quietly pick a different code - the caller
  asked for that exact string.

## TTL and expiry

- `ttlSeconds` is optional and must be positive. Absent means the link never expires.
- `expiresAt = createdAt + ttlSeconds`, measured from creation, not last use.
- Expiry is enforced on read. No background job deletes rows; the row stays for audit.
- An expired link returns `410 Gone`, not `404`: it existed and has lapsed.
- Requests to unknown or expired codes are not counted as hits.

## Redirects

- Always `302`, never `301`, and `Cache-Control: no-store`. A cached permanent redirect
  would bypass expiry and click counting.

## Short-code generation

- Both strategies sit behind `ShortCodeGenerator`; `shortener.strategy` picks one.
- Counter: unique by construction, passed through a keyed Feistel permutation so codes
  are not guessable, then Base62-encoded to 6 characters. `CounterBlockAllocator` hands
  out blocks; in production it is Redis `INCRBY` behind the same interface.
- Hash: can collide, so it needs a lookup and retry on every write.
- Any change here must keep `CodePermutationTest` (no two ids collide) and the
  16-thread no-duplicates test in `ShortCodeGeneratorTest` passing.

## Validation

- `url` must be http or https and at most 2048 characters.
- Test both the happy path and every failure status above for any change you make.
