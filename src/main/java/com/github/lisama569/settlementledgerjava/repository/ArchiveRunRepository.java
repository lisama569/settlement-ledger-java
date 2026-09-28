package com.github.lisama569.settlementledgerjava.repository;

import com.github.lisama569.settlementledgerjava.domain.ArchiveRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ArchiveRunRepository extends JpaRepository<ArchiveRun, Long> {

    Optional<ArchiveRun> findByRunId(String runId);
}
