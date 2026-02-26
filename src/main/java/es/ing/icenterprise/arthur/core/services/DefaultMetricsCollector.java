package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.domain.model.*;
import es.ing.icenterprise.arthur.core.domain.enums.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultMetricsCollector implements MetricsCollector {

    @Override
    public ProcessReport collect(List<Job> jobs, boolean manuallyTriggered) {
        Instant start = jobs.stream()
                .map(j -> j.getMetrics().getStartTime())
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(Instant.now());

        Instant end = jobs.stream()
                .map(j -> j.getMetrics().getEndTime())
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(Instant.now());

        List<JobSummary> jobSummaries = jobs.stream()
                .map(JobSummary::fromJob)
                .toList();

        List<LogEntry> allErrors = new ArrayList<>();
        List<LogEntry> allWarnings = new ArrayList<>();

        for (Job job : jobs) {
            for (LogEntry log : job.getLogs()) {
                if (log.getLevel() == LogLevel.ERROR) allErrors.add(log);
                if (log.getLevel() == LogLevel.WARN) allWarnings.add(log);
            }
        }

        AggregatedMetrics totals = AggregatedMetrics.fromJobs(jobs);
        Status overallStatus = determineOverallStatus(jobs);

        return ProcessReport.builder()
                .executionStart(start)
                .executionEnd(end)
                .manuallyTriggered(manuallyTriggered)
                .status(overallStatus)
                .jobs(jobSummaries)
                .errors(allErrors)
                .warnings(allWarnings)
                .totals(totals)
                .build();
    }

    private Status determineOverallStatus(List<Job> jobs) {
        if (jobs.isEmpty()) return Status.SUCCESS;

        boolean allSuccess = jobs.stream().allMatch(j -> j.getStatus() == Status.SUCCESS);
        if (allSuccess) return Status.SUCCESS;

        boolean allFailed = jobs.stream().allMatch(j -> j.getStatus() == Status.FAILED);
        if (allFailed) return Status.FAILED;

        return Status.PARTIAL;
    }
}
