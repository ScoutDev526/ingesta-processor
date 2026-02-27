package es.ing.icenterprise.arthur.adapters.outbound.report;

import es.ing.icenterprise.arthur.core.domain.enums.ExportFormat;
import es.ing.icenterprise.arthur.core.domain.model.JobSummary;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import es.ing.icenterprise.arthur.core.ports.outbound.ReportExporterPort;
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
