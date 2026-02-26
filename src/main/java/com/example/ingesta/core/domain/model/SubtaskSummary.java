package com.example.ingesta.core.domain.model;

import java.util.List;
import java.util.UUID;

public record SubtaskSummary(
    UUID id,
    String name,
    int order,
    Status status,
    long durationMs,
    List<SubtaskSummary> tasks,
    long recordsProcessed,
    long recordsFailed,
    long errorCount,
    List<LogEntry> errors
) {
    public static SubtaskSummary fromStep(Step step) {
        List<LogEntry> errorLogs = step.getLogs().stream()
                .filter(l -> l.getLevel() == LogLevel.ERROR)
                .toList();

        return new SubtaskSummary(
                step.getId(),
                step.getName(),
                step.getOrder(),
                step.getStatus(),
                step.getMetrics().getDurationMs(),
                List.of(),
                step.getMetrics().getRecordsProcessed(),
                step.getMetrics().getRecordsFailed(),
                step.getMetrics().getErrorCount(),
                errorLogs
        );
    }
}
