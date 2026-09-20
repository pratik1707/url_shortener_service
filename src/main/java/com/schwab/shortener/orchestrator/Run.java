package com.schwab.shortener.orchestrator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One requirement being taken through the SDLC. Persisted rather than held in memory
 * so a run can wait at an approval gate for as long as it takes - including across a
 * restart - and pick up where it left off.
 */
@Entity
@Table(name = "orchestration_run")
public class Run {

    @Id
    @Column(name = "id", length = 40, nullable = false)
    private String id;

    @Column(name = "requirement", length = 4000, nullable = false)
    private String requirement;

    /** greenfield | brownfield | ambiguous - drives how REQUIREMENTS interprets the input. */
    @Column(name = "scenario", length = 32, nullable = false)
    private String scenario;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private RunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    /** How many times a human has sent this run back for re-planning. Nullable for old rows. */
    @Column(name = "revisions")
    private Integer revisions;

    /** The most recent re-planning feedback; the full history is in the audit trail. */
    @Column(name = "reviewer_feedback", length = 2000)
    private String reviewerFeedback;

    protected Run() {
    }

    public Run(String id, String requirement, String scenario, Instant startedAt) {
        this.id = id;
        this.requirement = requirement;
        this.scenario = scenario;
        this.status = RunStatus.RUNNING;
        this.startedAt = startedAt;
    }

    public String getId() {
        return id;
    }

    public String getRequirement() {
        return requirement;
    }

    public String getScenario() {
        return scenario;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public int getRevisions() {
        return revisions == null ? 0 : revisions;
    }

    public String getReviewerFeedback() {
        return reviewerFeedback;
    }

    /** Records a request to re-plan. */
    public void recordRevision(String feedback) {
        this.revisions = getRevisions() + 1;
        this.reviewerFeedback = feedback == null || feedback.length() <= 2000
                ? feedback
                : feedback.substring(0, 2000);
    }

    public boolean isTerminal() {
        return status == RunStatus.COMPLETED
                || status == RunStatus.FAILED
                || status == RunStatus.REJECTED
                || status == RunStatus.ROLLED_BACK
                || status == RunStatus.STOPPED;
    }
}
