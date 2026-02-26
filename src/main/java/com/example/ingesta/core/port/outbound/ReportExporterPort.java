package com.example.ingesta.core.port.outbound;

import com.example.ingesta.core.domain.model.ExportFormat;
import com.example.ingesta.core.domain.model.ProcessReport;

/**
 * Serializes the report to the requested format.
 */
public interface ReportExporterPort {

    byte[] export(ProcessReport report, ExportFormat format);
}
