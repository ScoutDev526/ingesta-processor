package es.ing.icenterprise.arthur.core.ports.inbound;

import java.util.List;

/**
 * Command to trigger the ingestion process.
 * If jobFilter is empty, all jobs are executed.
 * If forceRedownload is true, files are downloaded even if already cached.
 */
public record ExecuteCommand(
    boolean manuallyTriggered,
    List<String> jobFilter,
    boolean forceRedownload
) {
    public static ExecuteCommand fromScheduler() {
        return new ExecuteCommand(false, List.of(), false);
    }

    public static ExecuteCommand fromManual() {
        return new ExecuteCommand(true, List.of(), false);
    }

    public static ExecuteCommand fromManual(List<String> jobFilter) {
        return new ExecuteCommand(true, jobFilter, false);
    }

    public static ExecuteCommand fromManual(List<String> jobFilter, boolean forceRedownload) {
        return new ExecuteCommand(true, jobFilter != null ? jobFilter : List.of(), forceRedownload);
    }

    public boolean shouldRunAll() {
        return jobFilter == null || jobFilter.isEmpty();
    }
}
