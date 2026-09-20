package com.schwab.shortener.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageExitCriteriaTest {

    @Test
    @DisplayName("empty output never passes")
    void emptyOutputFails() {
        assertTrue(StageExitCriteria.check(Stage.DOCS, "  ").isPresent());
        assertTrue(StageExitCriteria.check(Stage.IMPLEMENT, null).isPresent());
    }

    @Test
    @DisplayName("a design without a rollback plan is sent back")
    void designNeedsRollback() {
        assertTrue(StageExitCriteria.check(Stage.DESIGN, "Impacted surface: UrlService").isPresent());
        assertFalse(StageExitCriteria.check(Stage.DESIGN, "Impacted surface: UrlService\nRollback: revert").isPresent());
    }

    @Test
    @DisplayName("requirements must say how we will know it works")
    void requirementsNeedAcceptanceCriteria() {
        assertTrue(StageExitCriteria.check(Stage.REQUIREMENTS, "Normalized requirement: x").isPresent());
        assertFalse(StageExitCriteria.check(Stage.REQUIREMENTS, "Acceptance: returns 200").isPresent());
    }

    @Test
    @DisplayName("a test report only passes when nothing failed")
    void testReportMustShowNoFailures() {
        assertTrue(StageExitCriteria.check(Stage.TEST, "24 passed, 2 failed").isPresent());
        assertFalse(StageExitCriteria.check(Stage.TEST, "24 passed, 0 failed").isPresent());
    }
}
