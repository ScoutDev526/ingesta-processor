package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.domain.model.*;
import es.ing.icenterprise.arthur.core.domain.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMetricsCollectorTest {

    private DefaultMetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new DefaultMetricsCollector();
    }

    @Test
    @DisplayName("Should build report with SUCCESS when all jobs succeed")
    void allJobsSuccess() {
        Job job1 = createJob("job1", Status.SUCCESS, 100, 0);
        Job job2 = createJob("job2", Status.SUCCESS, 200, 0);

        ProcessReport report = collector.collect(List.of(job1, job2), false);

        assertThat(report.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(report.getTotals().totalJobs()).isEqualTo(2);
        assertThat(report.getTotals().successfulJobs()).isEqualTo(2);
        assertThat(report.getTotals().totalRecordsProcessed()).isEqualTo(300);
        assertThat(report.getTotals().overallSuccessRate()).isEqualTo(100.0);
        assertThat(report.isManuallyTriggered()).isFalse();
    }

    @Test
    @DisplayName("Should build report with FAILED when all jobs fail")
    void allJobsFailed() {
        Job job1 = createJob("job1", Status.FAILED, 0, 50);
        Job job2 = createJob("job2", Status.FAILED, 0, 30);

        ProcessReport report = collector.collect(List.of(job1, job2), true);

        assertThat(report.getStatus()).isEqualTo(Status.FAILED);
        assertThat(report.getTotals().failedJobs()).isEqualTo(2);
        assertThat(report.getTotals().overallSuccessRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should build report with PARTIAL when mixed results")
    void mixedResults() {
        Job job1 = createJob("job1", Status.SUCCESS, 100, 0);
        Job job2 = createJob("job2", Status.FAILED, 10, 40);

        ProcessReport report = collector.collect(List.of(job1, job2), false);

        assertThat(report.getStatus()).isEqualTo(Status.PARTIAL);
        assertThat(report.getTotals().successfulJobs()).isEqualTo(1);
        assertThat(report.getTotals().failedJobs()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should handle empty job list")
    void emptyJobList() {
        ProcessReport report = collector.collect(List.of(), true);

        assertThat(report.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(report.getTotals().totalJobs()).isEqualTo(0);
        assertThat(report.getJobs()).isEmpty();
    }

    @Test
    @DisplayName("Should collect error and warning logs")
    void shouldCollectErrorsAndWarnings() {
        Job job = new Job("test", "/file.xlsx", FileType.EXCEL);
        job.start();
        job.addLog(LogEntry.error("test", "Something failed"));
        job.addLog(LogEntry.warn("test", "Something suspicious"));
        job.addLog(LogEntry.info("test", "All good"));
        job.complete(Status.PARTIAL);

        ProcessReport report = collector.collect(List.of(job), false);

        assertThat(report.getErrors()).hasSize(1);
        assertThat(report.getWarnings()).hasSize(1);
    }

    private Job createJob(String name, Status status, long processed, long failed) {
        Job job = new Job(name, "/data/" + name + ".xlsx", FileType.EXCEL);
        job.start();
        job.getMetrics().incrementProcessed(processed);
        job.getMetrics().incrementFailed(failed);
        job.complete(status);
        return job;
    }
}
