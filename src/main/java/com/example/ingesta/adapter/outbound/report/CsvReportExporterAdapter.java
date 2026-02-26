package com.example.ingesta.adapter.outbound.report;

import com.example.ingesta.core.domain.model.ExportFormat;
import com.example.ingesta.core.domain.model.JobSummary;
import com.example.ingesta.core.domain.model.ProcessReport;
import com.example.ingesta.core.port.outbound.ReportExporterPort;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class CsvReportExporterAdapter implements ReportExporterPort {

    @Override
    public byte[] export(ProcessReport report, ExportFormat format) {
        if (format != ExportFormat.CSV) {
            throw new UnsupportedOperationException("Only CSV export is supported. Requested: " + format);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("Job Name,File Path,File Type,Status,Duration (ms),Records Processed,Records Failed\n");

        for (JobSummary job : report.getJobs()) {
            csv.append(String.format("%s,%s,%s,%s,%d,%d,%d\n",
                    job.name(), job.filePath(), job.fileType(),
                    job.status(), job.durationMs(),
                    job.recordsProcessed(), job.recordsFailed()));
        }

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
}
