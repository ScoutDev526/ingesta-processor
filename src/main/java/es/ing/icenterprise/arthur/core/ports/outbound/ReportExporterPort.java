package es.ing.icenterprise.arthur.core.ports.outbound;

import es.ing.icenterprise.arthur.core.domain.enums.ExportFormat;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;

/**
 * Serializes the report to the requested format.
 */
public interface ReportExporterPort {

    byte[] export(ProcessReport report, ExportFormat format);
}
