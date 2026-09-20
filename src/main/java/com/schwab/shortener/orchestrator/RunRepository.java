package com.schwab.shortener.orchestrator;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RunRepository extends JpaRepository<Run, String> {
    List<Run> findAllByOrderByStartedAtDesc();
}
