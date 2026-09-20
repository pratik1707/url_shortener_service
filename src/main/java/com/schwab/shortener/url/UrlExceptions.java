package com.schwab.shortener.url;

public final class UrlExceptions {

    private UrlExceptions() {
    }

    /** The requested code does not exist. Maps to 404. */
    public static class NotFound extends RuntimeException {
        public NotFound(String code) {
            super("no such short code: " + code);
        }
    }

    /** The code existed but its TTL has passed. Maps to 410 - the distinction matters. */
    public static class Expired extends RuntimeException {
        public Expired(String code) {
            super("short code has expired: " + code);
        }
    }

    /** A caller-chosen alias is already taken. Maps to 409. */
    public static class AliasTaken extends RuntimeException {
        public AliasTaken(String alias) {
            super("alias already in use: " + alias);
        }
    }

    /** Generation could not find a free code within the retry budget. Maps to 503. */
    public static class GenerationExhausted extends RuntimeException {
        public GenerationExhausted(int attempts) {
            super("could not generate a free short code after " + attempts + " attempts");
        }
    }
}
