package es.ing.icenterprise.arthur.core.domain.factory;

import es.ing.icenterprise.arthur.core.domain.model.*;
import es.ing.icenterprise.arthur.core.domain.enums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelTest {

    @Test
    @DisplayName("Job lifecycle: PENDING -> RUNNING -> SUCCESS")
    void jobLifecycle() {
        Job job = new Job("test-job", "/data/test.xlsx", FileType.EXCEL);
        assertThat(job.getStatus()).isEqualTo(Status.PENDING);

        job.start();
        assertThat(job.getStatus()).isEqualTo(Status.RUNNING);
        assertThat(job.getMetrics().getStartTime()).isNotNull();

        job.complete(Status.SUCCESS);
        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(job.getMetrics().getEndTime()).isNotNull();
        assertThat(job.getMetrics().getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Job skip sets SKIPPED status with warning log")
    void jobSkip() {
        Job job = new Job("test-job", "/data/test.xlsx", FileType.EXCEL);
        job.skip("File not available");

        assertThat(job.getStatus()).isEqualTo(Status.SKIPPED);
        assertThat(job.getLogs()).hasSize(1);
        assertThat(job.getLogs().get(0).getLevel()).isEqualTo(LogLevel.WARN);
    }

    @Test
    @DisplayName("Task with steps tracks metrics correctly")
    void taskWithSteps() {
        Task task = new Task("transform", TaskType.TRANSFORMATION, 1, true);

        Step step1 = new Step("trim", StepType.TRIM, 1);
        Step step2 = new Step("uppercase", StepType.UPPERCASE, 2);
        task.addStep(step1);
        task.addStep(step2);

        assertThat(task.getSteps()).hasSize(2);
        assertThat(task.isStopOnFailure()).isTrue();
    }

    @Test
    @DisplayName("Metrics tracks processed/failed/skipped records")
    void metricsTracking() {
        Metrics metrics = new Metrics();
        metrics.start();

        metrics.incrementProcessed(100);
        metrics.incrementFailed(5);
        metrics.incrementSkipped(3);
        metrics.incrementErrors();
        metrics.incrementWarnings();
        metrics.addCustomMetric("batchSize", 500);

        metrics.finish(Status.PARTIAL);

        assertThat(metrics.getRecordsProcessed()).isEqualTo(100);
        assertThat(metrics.getRecordsFailed()).isEqualTo(5);
        assertThat(metrics.getRecordsSkipped()).isEqualTo(3);
        assertThat(metrics.getErrorCount()).isEqualTo(1);
        assertThat(metrics.getWarningCount()).isEqualTo(1);
        assertThat(metrics.getCustomMetrics()).containsEntry("batchSize", 500);
        assertThat(metrics.getStatus()).isEqualTo(Status.PARTIAL);
    }

    @Test
    @DisplayName("LogEntry factory methods create correct levels")
    void logEntryFactoryMethods() {
        LogEntry info = LogEntry.info("source", "message");
        assertThat(info.getLevel()).isEqualTo(LogLevel.INFO);

        LogEntry warn = LogEntry.warn("source", "warning");
        assertThat(warn.getLevel()).isEqualTo(LogLevel.WARN);

        LogEntry error = LogEntry.error("source", "error", new RuntimeException("test"));
        assertThat(error.getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(error.getThrowable()).isNotNull();
    }

    @Test
    @DisplayName("LogEntry supports context map")
    void logEntryContext() {
        LogEntry entry = LogEntry.info("source", "message")
                .withContext("jobName", "test-job")
                .withContext("step", "trim");

        assertThat(entry.getContext())
                .containsEntry("jobName", "test-job")
                .containsEntry("step", "trim");
    }

    @Test
    @DisplayName("AggregatedMetrics calculates correct totals")
    void aggregatedMetrics() {
        Job job1 = createCompletedJob("job1", Status.SUCCESS, 100, 0);
        Job job2 = createCompletedJob("job2", Status.PARTIAL, 80, 20);
        Job job3 = createCompletedJob("job3", Status.FAILED, 0, 50);

        AggregatedMetrics totals = AggregatedMetrics.fromJobs(List.of(job1, job2, job3));

        assertThat(totals.totalJobs()).isEqualTo(3);
        assertThat(totals.successfulJobs()).isEqualTo(1);
        assertThat(totals.failedJobs()).isEqualTo(1);
        assertThat(totals.partialJobs()).isEqualTo(1);
        assertThat(totals.totalRecordsProcessed()).isEqualTo(180);
        assertThat(totals.totalRecordsFailed()).isEqualTo(70);
        assertThat(totals.overallSuccessRate()).isCloseTo(33.3, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    @DisplayName("JobSummary created from Job includes all task summaries")
    void jobSummaryFromJob() {
        Job job = new Job("test", "/file.xlsx", FileType.EXCEL);
        Task task = new Task("transform", TaskType.TRANSFORMATION, 1, false);
        Step step = new Step("trim", StepType.TRIM, 1);
        task.addStep(step);
        job.addTask(task);

        job.start();
        job.complete(Status.SUCCESS);

        JobSummary summary = JobSummary.fromJob(job);
        assertThat(summary.name()).isEqualTo("test");
        assertThat(summary.tasks()).hasSize(1);
        assertThat(summary.tasks().get(0).tasks()).hasSize(1);
    }

    @Test
    @DisplayName("ProcessReport builder creates valid report")
    void processReportBuilder() {
        java.time.Instant start = java.time.Instant.now();
        java.time.Instant end = start.plusMillis(1000);

        ProcessReport report = ProcessReport.builder()
                .executionStart(start)
                .executionEnd(end)
                .manuallyTriggered(true)
                .status(Status.SUCCESS)
                .jobs(List.of())
                .errors(List.of())
                .warnings(List.of())
                .totals(AggregatedMetrics.fromJobs(List.of()))
                .build();

        assertThat(report.getId()).isNotNull();
        assertThat(report.getGeneratedAt()).isNotNull();
        assertThat(report.getTotalDurationMs()).isEqualTo(1000);
        assertThat(report.isManuallyTriggered()).isTrue();
        assertThat(report.getStatus()).isEqualTo(Status.SUCCESS);
    }

    private Job createCompletedJob(String name, Status status, long processed, long failed) {
        Job job = new Job(name, "/file.xlsx", FileType.EXCEL);
        job.start();
        job.getMetrics().incrementProcessed(processed);
        job.getMetrics().incrementFailed(failed);
        job.complete(status);
        return job;
    }
}
