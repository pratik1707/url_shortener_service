package com.schwab.shortener.orchestrator;

public enum StageStatus {
    PENDING,
    RUNNING,
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    REJECTED,
    ROLLED_BACK,
    SKIPPED,
    /** Taken out of the plan by an upstream decision, e.g. a docs-only design. Counts as satisfied. */
    NOT_REQUIRED
}
