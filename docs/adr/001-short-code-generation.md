# ADR-001: Short Code Generation Strategy

**Status:** Accepted
**Date:** 2026-09-18

## Context

The service must generate a short code for every submitted URL. Three constraints
pull against each other:

1. **Uniqueness** - two different URLs must never receive the same code.
2. **Brevity** - the code is the product; every extra character is a cost.
3. **Efficiency** - generation must not become a write-path bottleneck.

A fourth constraint applies in this environment and is not present in the generic
form of this problem:

4. **Non-enumerability** - codes must not be walkable by an attacker. This service
   is being designed for a context where URL enumeration is an information-disclosure
   risk, not a cosmetic concern.

Target scale for sizing decisions: 1B stored URLs, ~100M daily active users,
read-to-write ratio on the order of 1000:1.

## Options Considered

### Option 1 - Prefix of the input URL

Take the first N characters of the submitted URL.

Rejected immediately. Any two URLs sharing a prefix collide, and URL prefixes are
highly clustered in practice (`https://www.`). Fails constraint 1 outright.

### Option 2 - Hash function (SHA-256 → base62, truncated)

Canonicalize the URL, hash it, base62-encode, take the first 8 characters.

**For:**
- Stateless. No shared coordination between write instances.
- Deterministic, which gives free deduplication if the same URL is submitted twice.
- High entropy; output is not enumerable.

**Against:**
- Collisions are possible and become non-negligible at scale. With a code space
  of size `|S|` and `n` codes in use, the probability that the next code collides
  is `n / |S|`. This requires a uniqueness check on every write - a database
  round-trip on the write path - plus bounded retry with a salt on conflict.
- Reducing collision probability means longer codes, which fights constraint 2.
- Determinism is a liability where the same URL should be able to receive
  distinct codes, or where guessability of a code from its target is undesirable.
  Mitigable with an HMAC and a secret salt, at the cost of losing deduplication.

### Option 3 - Monotonic counter → base62 (**chosen**)

Increment a shared counter per creation, base62-encode the value.

**For:**
- **Collisions are structurally impossible.** No uniqueness check, no retry loop,
  no database round-trip on the write path to verify availability.
- Compact. 1B URLs encodes to 6 characters (`1,000,000,000` → `15ftgG`). The
  6-character space holds ~56B codes before a 7th character is needed, which
  then holds ~3.5T.
- Cheap. An atomic increment and an encode.
- Reversible to the underlying id, which is useful for lookup and for operations.

**Against:**
- Requires a coordination point. All write instances must agree on the counter.
- **Sequential output is enumerable.** This is the significant objection and is
  addressed below.
- Code length grows over time rather than being fixed.

## Decision

**Option 3, with a reversible obfuscation step applied before encoding.**

```
id          = counter.increment()        // atomic, globally unique
obfuscated  = transform(id, secret)      // reversible, order-destroying
short_code  = base62_encode(obfuscated)
```

### Counter implementation

Redis `INCR`. Redis is single-threaded and processes one command at a time, so
two concurrent increments always return different values - the uniqueness
guarantee comes from the datastore's execution model rather than from application
locking.

**Batching:** each write instance requests a block of counter values (e.g. 1000)
with a single `INCRBY` and serves codes from that block locally. This reduces
Redis traffic on the write path by three orders of magnitude and keeps the write
service available for the life of its block during a brief Redis interruption.
The cost is gaps in the sequence when an instance dies holding an unused block,
which is acceptable - the counter guarantees uniqueness, not density.

**Availability:** Redis Sentinel or Cluster for failover. The counter is the one
piece of hard shared state in the write path, so it is the piece that needs an
explicit failover story. Multi-region deployments are assigned disjoint counter
ranges rather than sharing a counter across regions.

### Obfuscation

A raw counter maps `1, 2, 3` to `b, c, d`. An attacker who obtains one code can
enumerate the entire corpus by walking adjacent values. Given that this service
is specified for a security-sensitive environment, that is not an acceptable
default.

The transform must be **reversible** (so a code can still be decoded to its id),
**order-destroying** (so adjacency in the id space does not imply adjacency in the
code space), and **cheap** (it sits on the write path).

A keyed Feistel permutation over the integer space satisfies all three and is a
few dozen lines. An XOR with a secret key is simpler but weaker - it preserves
structure under analysis - and is noted here as the lighter-weight alternative
rather than the recommendation.

The secret is configuration, not code, and rotating it changes the mapping for
*new* codes only; existing codes remain decodable because the id is also stored.

### Custom aliases

Custom aliases bypass the counter entirely and are inserted directly, subject to
a uniqueness constraint. They are the one path where a collision is possible, and
there it is correct to surface the conflict to the user rather than retry - the
user asked for a specific string.

## Consequences

- The write path has no read-before-write for uniqueness. Generation is an atomic
  increment plus arithmetic.
- A `UNIQUE` constraint on the short code column remains in place as a correctness
  backstop. It should never fire for generated codes; if it does, that is a bug
  or a counter-range misconfiguration, and it is better to find out via a
  constraint violation than via a silently overwritten row.
- Redis becomes a write-path dependency. Batching bounds the blast radius of a
  Redis outage to "writes continue until blocks are exhausted."
- Codes are not guessable from the target URL and are not enumerable from each
  other.
- Deduplication of identical URLs is **not** provided, unlike the hash approach.
  If it is later required, it is an independent index on the canonicalized URL,
  not a change to code generation.

## Rejected Alternative, Revisited

Option 2 remains the better choice in a deployment with no tolerance for shared
state on the write path - a fully partitioned, coordination-free service would
prefer the hash and accept the retry cost. That is not this system: a single
Redis with failover is an acceptable dependency here, and the write volume
(~1 row/second at target scale) is far below anything that would strain it.
