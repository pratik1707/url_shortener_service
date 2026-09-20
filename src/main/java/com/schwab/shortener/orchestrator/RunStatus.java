package com.schwab.shortener.orchestrator;

public enum RunStatus {
    /** Stages are executing. */
    RUNNING,
    /** Parked at a human gate. Nothing proceeds until someone decides. */
    AWAITING_APPROVAL,
    COMPLETED,
    /** A stage exhausted its retries and the run could not be rolled back cleanly. */
    FAILED,
    /** A human declined at a gate. */
    REJECTED,
    /** A failure triggered compensation; completed work was undone. */
    ROLLED_BACK,
    /** Halted by an explicit safe-stop request. */
    STOPPED
}
