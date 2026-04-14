package es.ing.icenterprise.arthur.adapters.outbound.report;

import es.ing.icenterprise.arthur.core.domain.enums.ExportFormat;
import es.ing.icenterprise.arthur.core.domain.enums.FileType;
import es.ing.icenterprise.arthur.core.domain.enums.Status;
import es.ing.icenterprise.arthur.core.domain.model.AggregatedMetrics;
import es.ing.icenterprise.arthur.core.domain.model.JobSummary;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvReportExporterAdapterTest {

    private final CsvReportExporterAdapter adapter = new CsvReportExporterAdapter();

    @Test
    @DisplayName("export produces CSV with header and one data row per job")
    void exportProducesCsvWithHeaderAndRows() {
        ProcessReport report = buildReportWithJob("my-job", Status.SUCCESS);

        byte[] csv = adapter.export(report, ExportFormat.CSV);
        String content = new String(csv, StandardCharsets.UTF_8);

        assertThat(content).startsWith("Job Name,File Path,File Type,Status,Duration (ms),Records Processed,Records Failed");
        assertThat(content).contains("my-job");
        assertThat(content).contains("SUCCESS");
    }

    @Test
    @DisplayName("export for empty job list produces only the header row")
    void exportEmptyJobsProducesHeaderOnly() {
        ProcessReport report = buildEmptyReport();

        byte[] csv = adapter.export(report, ExportFormat.CSV);
        String content = new String(csv, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");

        assertThat(lines).hasSize(1);
        assertThat(lines[0]).startsWith("Job Name");
    }

    @Test
    @DisplayName("export throws UnsupportedOperationException for non-CSV format")
    void exportThrowsForNonCsvFormat() {
        ProcessReport report = buildEmptyReport();
        assertThatThrownBy(() -> adapter.export(report, ExportFormat.EXCEL))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Only CSV export is supported");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ProcessReport buildEmptyReport() {
        Instant start = Instant.now().minusSeconds(1);
        AggregatedMetrics metrics = new AggregatedMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0);
        return ProcessReport.builder()
                .executionStart(start).executionEnd(Instant.now())
                .status(Status.SUCCESS).totals(metrics)
                .build();
    }

    private ProcessReport buildReportWithJob(String name, Status status) {
        JobSummary job = new JobSummary(UUID.randomUUID(), name, "/data/f.xlsx",
                FileType.EXCEL, status, 100L, List.of(), 10L, 0L, 0L, 0L, List.of());
        Instant start = Instant.now().minusSeconds(1);
        AggregatedMetrics metrics = new AggregatedMetrics(1, 1, 0, 0, 10, 0, 0, 0, 0, 100.0);
        return ProcessReport.builder()
                .executionStart(start).executionEnd(Instant.now())
                .status(status).jobs(List.of(job)).totals(metrics)
                .build();
    }
}
