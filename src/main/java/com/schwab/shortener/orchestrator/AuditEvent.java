package com.schwab.shortener.orchestrator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Append-only record of what the orchestrator did and what humans decided. Rows are
 * never updated or deleted - a correction is a new row. That is what makes it evidence
 * rather than a status display.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", length = 40, nullable = false)
    private String runId;

    @Column(name = "at", nullable = false)
    private Instant at;

    /** STAGE_STARTED, STAGE_COMPLETED, RETRY, APPROVAL_GRANTED, ROLLBACK, POLICY_BLOCK, ... */
    @Column(name = "event_type", length = 48, nullable = false)
    private String eventType;

    @Column(name = "stage", length = 32)
    private String stage;

    /** Who caused it: "orchestrator", or the identity of the human at a gate. */
    @Column(name = "actor", length = 128, nullable = false)
    private String actor;

    @Column(name = "detail", length = 4000)
    private String detail;

    protected AuditEvent() {
    }

    public AuditEvent(String runId, Instant at, String eventType, String stage, String actor, String detail) {
        this.runId = runId;
        this.at = at;
        this.eventType = eventType;
        this.stage = stage;
        this.actor = actor;
        this.detail = detail == null || detail.length() <= 4000 ? detail : detail.substring(0, 4000);
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public Instant getAt() {
        return at;
    }

    public String getEventType() {
        return eventType;
    }

    public String getStage() {
        return stage;
    }

    public String getActor() {
        return actor;
    }

    public String getDetail() {
        return detail;
    }
}
