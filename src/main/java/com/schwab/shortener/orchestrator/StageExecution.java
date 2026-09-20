package com.schwab.shortener.orchestrator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One stage of one run: status, attempts, what the agent produced, how long it took.
 * Together these rows are the decision lineage - each stage records the output the
 * next ones consumed.
 */
@Entity
@Table(name = "stage_execution")
public class StageExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", length = 40, nullable = false)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 32, nullable = false)
    private Stage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private StageStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "output", length = 8000)
    private String output;

    @Column(name = "error", length = 2000)
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Which agent produced the output - part of the decision lineage. */
    @Column(name = "agent", length = 64)
    private String agent;

    /** True when the primary agent gave up and the fallback agent produced the output. */
    @Column(name = "used_fallback")
    private Boolean usedFallback;

    protected StageExecution() {
    }

    public StageExecution(String runId, Stage stage) {
        this.runId = runId;
        this.stage = stage;
        this.status = StageStatus.PENDING;
        this.attempts = 0;
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public Stage getStage() {
        return stage;
    }

    public StageStatus getStatus() {
        return status;
    }

    public void setStatus(StageStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output == null || output.length() <= 8000
                ? output
                : output.substring(0, 8000);
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error == null || error.length() <= 2000
                ? error
                : error.substring(0, 2000);
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public boolean isUsedFallback() {
        return Boolean.TRUE.equals(usedFallback);
    }

    public void setUsedFallback(boolean usedFallback) {
        this.usedFallback = usedFallback;
    }

    /**
     * Puts the stage back to where it was before it ever ran, because something it
     * depended on has changed and its old output is no longer valid.
     */
    public void resetForReplan() {
        this.status = StageStatus.PENDING;
        this.attempts = 0;
        this.output = null;
        this.error = null;
        this.startedAt = null;
        this.finishedAt = null;
        this.durationMs = null;
        this.agent = null;
        this.usedFallback = null;
    }
}
