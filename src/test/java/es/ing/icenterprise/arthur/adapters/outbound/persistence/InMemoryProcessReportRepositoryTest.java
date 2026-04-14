package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import es.ing.icenterprise.arthur.core.domain.enums.Status;
import es.ing.icenterprise.arthur.core.domain.model.AggregatedMetrics;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryProcessReportRepositoryTest {

    private final InMemoryProcessReportRepository repo = new InMemoryProcessReportRepository();

    @Test
    @DisplayName("save and findById returns the stored report")
    void saveAndFindReturnsStoredReport() {
        ProcessReport report = buildReport();
        repo.save(report);

        Optional<ProcessReport> found = repo.findById(report.getId());
        assertThat(found).isPresent().contains(report);
    }

    @Test
    @DisplayName("findById returns empty for unknown UUID")
    void findByIdReturnsEmptyForUnknown() {
        Optional<ProcessReport> found = repo.findById(UUID.randomUUID());
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("save returns the same report object")
    void saveReturnsSameReport() {
        ProcessReport report = buildReport();
        ProcessReport returned = repo.save(report);
        assertThat(returned).isSameAs(report);
    }

    @Test
    @DisplayName("multiple reports stored independently")
    void multipleReportsStoredIndependently() {
        ProcessReport r1 = buildReport();
        ProcessReport r2 = buildReport();
        repo.save(r1);
        repo.save(r2);

        assertThat(repo.findById(r1.getId())).isPresent();
        assertThat(repo.findById(r2.getId())).isPresent();
        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private ProcessReport buildReport() {
        Instant start = Instant.now().minusSeconds(1);
        AggregatedMetrics metrics = new AggregatedMetrics(1, 1, 0, 0, 5, 0, 0, 0, 0, 100.0);
        return ProcessReport.builder()
                .executionStart(start).executionEnd(Instant.now())
                .status(Status.SUCCESS).totals(metrics)
                .build();
    }
}
