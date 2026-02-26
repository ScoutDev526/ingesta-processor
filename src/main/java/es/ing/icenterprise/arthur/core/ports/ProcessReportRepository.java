package es.ing.icenterprise.arthur.core.ports;

import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for the ProcessReport if needed.
 */
public interface ProcessReportRepository {

    ProcessReport save(ProcessReport report);

    Optional<ProcessReport> findById(UUID id);
}
