package com.example.ingesta.core.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ProcessReport {

    private final UUID id;
    private final Instant generatedAt;
    private final Instant executionStart;
    private final Instant executionEnd;
    private final long totalDurationMs;
    private final boolean manuallyTriggered;
    private final Status status;
    private final List<JobSummary> jobs;
    private final List<LogEntry> errors;
    private final List<LogEntry> warnings;
    private final AggregatedMetrics totals;

    private ProcessReport(Builder builder) {
        this.id = UUID.randomUUID();
        this.generatedAt = Instant.now();
        this.executionStart = builder.executionStart;
        this.executionEnd = builder.executionEnd;
        this.totalDurationMs = Duration.between(builder.executionStart, builder.executionEnd).toMillis();
        this.manuallyTriggered = builder.manuallyTriggered;
        this.status = builder.status;
        this.jobs = builder.jobs;
        this.errors = builder.errors;
        this.warnings = builder.warnings;
        this.totals = builder.totals;
    }

    public static Builder builder() { return new Builder(); }

    public UUID getId() { return id; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getExecutionStart() { return executionStart; }
    public Instant getExecutionEnd() { return executionEnd; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public boolean isManuallyTriggered() { return manuallyTriggered; }
    public Status getStatus() { return status; }
    public List<JobSummary> getJobs() { return jobs; }
    public List<LogEntry> getErrors() { return errors; }
    public List<LogEntry> getWarnings() { return warnings; }
    public AggregatedMetrics getTotals() { return totals; }

    public static class Builder {
        private Instant executionStart;
        private Instant executionEnd;
        private boolean manuallyTriggered;
        private Status status;
        private List<JobSummary> jobs = List.of();
        private List<LogEntry> errors = List.of();
        private List<LogEntry> warnings = List.of();
        private AggregatedMetrics totals;

        public Builder executionStart(Instant start) { this.executionStart = start; return this; }
        public Builder executionEnd(Instant end) { this.executionEnd = end; return this; }
        public Builder manuallyTriggered(boolean manual) { this.manuallyTriggered = manual; return this; }
        public Builder status(Status status) { this.status = status; return this; }
        public Builder jobs(List<JobSummary> jobs) { this.jobs = jobs; return this; }
        public Builder errors(List<LogEntry> errors) { this.errors = errors; return this; }
        public Builder warnings(List<LogEntry> warnings) { this.warnings = warnings; return this; }
        public Builder totals(AggregatedMetrics totals) { this.totals = totals; return this; }
        public ProcessReport build() { return new ProcessReport(this); }
    }
}
