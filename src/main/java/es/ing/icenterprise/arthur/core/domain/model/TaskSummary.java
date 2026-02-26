package es.ing.icenterprise.arthur.core.domain.model;

import es.ing.icenterprise.arthur.core.domain.enums.*;

import java.util.List;
import java.util.UUID;

public record TaskSummary(
    UUID id,
    String name,
    int order,
    Status status,
    long durationMs,
    List<SubtaskSummary> tasks,
    long recordsProcessed,
    long recordsFailed
) {
    public static TaskSummary fromTask(Task task) {
        List<SubtaskSummary> subtaskSummaries = task.getSteps().stream()
                .map(SubtaskSummary::fromStep)
                .toList();

        return new TaskSummary(
                task.getId(),
                task.getName(),
                task.getOrder(),
                task.getStatus(),
                task.getMetrics().getDurationMs(),
                subtaskSummaries,
                task.getMetrics().getRecordsProcessed(),
                task.getMetrics().getRecordsFailed()
        );
    }
}
