package es.ing.icenterprise.arthur.core.domain.model;

import es.ing.icenterprise.arthur.core.domain.enums.*;

import java.util.List;

public record AggregatedMetrics(
    long totalJobs,
    long successfulJobs,
    long failedJobs,
    long partialJobs,
    long totalRecordsProcessed,
    long totalRecordsFailed,
    long totalRecordsSkipped,
    long totalErrors,
    long totalWarnings,
    double overallSuccessRate
) {
    public static AggregatedMetrics fromJobs(List<Job> jobs) {
        long total = jobs.size();
        long successful = jobs.stream().filter(j -> j.getStatus() == Status.SUCCESS).count();
        long failed = jobs.stream().filter(j -> j.getStatus() == Status.FAILED).count();
        long partial = jobs.stream().filter(j -> j.getStatus() == Status.PARTIAL).count();

        long recordsProcessed = jobs.stream().mapToLong(j -> j.getMetrics().getRecordsProcessed()).sum();
        long recordsFailed = jobs.stream().mapToLong(j -> j.getMetrics().getRecordsFailed()).sum();
        long recordsSkipped = jobs.stream().mapToLong(j -> j.getMetrics().getRecordsSkipped()).sum();
        long errors = jobs.stream().mapToLong(j -> j.getMetrics().getErrorCount()).sum();
        long warnings = jobs.stream().mapToLong(j -> j.getMetrics().getWarningCount()).sum();

        double successRate = total > 0 ? (double) successful / total * 100.0 : 0.0;

        return new AggregatedMetrics(
                total, successful, failed, partial,
                recordsProcessed, recordsFailed, recordsSkipped,
                errors, warnings, successRate
        );
    }
}
