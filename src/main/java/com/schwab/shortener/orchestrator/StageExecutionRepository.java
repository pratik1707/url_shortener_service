package com.schwab.shortener.orchestrator;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StageExecutionRepository extends JpaRepository<StageExecution, Long> {
    List<StageExecution> findByRunIdOrderByIdAsc(String runId);
}
