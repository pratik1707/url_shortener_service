package com.schwab.shortener.url.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodePermutationTest {

    private final CodePermutation permutation = new CodePermutation(0xC0FFEEL);

    @Test
    @DisplayName("shuffling a value and unshuffling it gives back the original")
    void shuffleThenUnshuffleGivesBackTheOriginal() {
        for (long id = 0; id < 50_000; id++) {
            assertEquals(id, permutation.invert(permutation.apply(id)));
        }
    }

    /**
     * The counter's whole value is that it cannot collide. If the shuffle mapped two
     * ids to the same output, that guarantee would be gone - so this is the test that
     * protects the reason we chose the counter at all.
     */
    @Test
    @DisplayName("no two counter values produce the same code")
    void noTwoCounterValuesProduceTheSameCode() {
        Set<Long> seen = new HashSet<>();
        for (long id = 0; id < 200_000; id++) {
            assertTrue(seen.add(permutation.apply(id)), "duplicate produced for id " + id);
        }
    }

    @Test
    @DisplayName("results always land inside the six-character space")
    void resultsStayInsideTheCodeSpace() {
        for (long id = 0; id < 50_000; id++) {
            long result = permutation.apply(id);
            assertTrue(result >= 0 && result < Base62.DOMAIN);
        }
    }

    /**
     * Without this property a sequential counter leaks the whole corpus: get one code,
     * add one, get the next person's link.
     */
    @Test
    @DisplayName("consecutive ids do not produce neighbouring codes")
    void consecutiveIdsDoNotProduceNeighbouringCodes() {
        int neighbours = 0;
        for (long id = 0; id < 10_000; id++) {
            if (Math.abs(permutation.apply(id + 1) - permutation.apply(id)) <= 1) {
                neighbours++;
            }
        }
        assertEquals(0, neighbours);
    }

    @Test
    @DisplayName("a different secret produces a different mapping")
    void aDifferentSecretProducesADifferentMapping() {
        CodePermutation other = new CodePermutation(0xDEADBEEFL);
        int differences = 0;
        for (long id = 0; id < 1000; id++) {
            if (permutation.apply(id) != other.apply(id)) {
                differences++;
            }
        }
        assertTrue(differences > 990, "expected nearly all mappings to differ, got " + differences);
        assertNotEquals(permutation.apply(1), other.apply(1));
    }
}
