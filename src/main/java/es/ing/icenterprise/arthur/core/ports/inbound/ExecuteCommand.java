package es.ing.icenterprise.arthur.core.ports.inbound;

import java.util.List;

/**
 * Command to trigger the ingestion process.
 * If jobFilter is empty, all jobs are executed.
 */
public record ExecuteCommand(
    boolean manuallyTriggered,
    List<String> jobFilter
) {
    public static ExecuteCommand fromScheduler() {
        return new ExecuteCommand(false, List.of());
    }

    public static ExecuteCommand fromManual() {
        return new ExecuteCommand(true, List.of());
    }

    public static ExecuteCommand fromManual(List<String> jobFilter) {
        return new ExecuteCommand(true, jobFilter);
    }

    public boolean shouldRunAll() {
        return jobFilter == null || jobFilter.isEmpty();
    }
}
