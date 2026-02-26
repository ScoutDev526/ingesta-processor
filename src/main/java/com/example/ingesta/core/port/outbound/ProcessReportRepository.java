package com.example.ingesta.core.port.outbound;

import com.example.ingesta.core.domain.model.ProcessReport;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for the ProcessReport if needed.
 */
public interface ProcessReportRepository {

    ProcessReport save(ProcessReport report);

    Optional<ProcessReport> findById(UUID id);
}
