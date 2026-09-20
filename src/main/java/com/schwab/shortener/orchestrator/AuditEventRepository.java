package com.schwab.shortener.orchestrator;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByRunIdOrderByIdAsc(String runId);
}
