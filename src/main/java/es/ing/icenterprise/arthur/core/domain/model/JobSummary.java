package es.ing.icenterprise.arthur.core.domain.model;

import java.util.List;
import java.util.UUID;

public record JobSummary(
    UUID id,
    String name,
    String filePath,
    FileType fileType,
    Status status,
    long durationMs,
    List<TaskSummary> tasks,
    long recordsProcessed,
    long recordsFailed,
    long recordsSkipped,
    long errorCount,
    List<LogEntry> errors
) {
    public static JobSummary fromJob(Job job) {
        List<TaskSummary> taskSummaries = job.getTasks().stream()
                .map(TaskSummary::fromTask)
                .toList();

        List<LogEntry> errorLogs = job.getLogs().stream()
                .filter(l -> l.getLevel() == LogLevel.ERROR)
                .toList();

        return new JobSummary(
                job.getId(),
                job.getName(),
                job.getFilePath(),
                job.getFileType(),
                job.getStatus(),
                job.getMetrics().getDurationMs(),
                taskSummaries,
                job.getMetrics().getRecordsProcessed(),
                job.getMetrics().getRecordsFailed(),
                job.getMetrics().getRecordsSkipped(),
                job.getMetrics().getErrorCount(),
                errorLogs
        );
    }
}
