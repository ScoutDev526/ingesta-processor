package es.ing.icenterprise.arthur.core.ports.outbound;

import es.ing.icenterprise.arthur.core.domain.model.Job;

import java.util.List;

/**
 * Port for generating an Excel execution log report from a completed list of jobs.
 */
public interface ExecutionLogExporterPort {

    /**
     * Exports all job logs (job-level + task-level + step-level) into an Excel workbook.
     *
     * @param jobs        the completed job list
     * @param reportTitle title used for the global (first) sheet
     * @return the Excel bytes (.xlsx format)
     */
    byte[] export(List<Job> jobs, String reportTitle);
}
