package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.domain.model.*;
import es.ing.icenterprise.arthur.core.domain.enums.*;
import es.ing.icenterprise.arthur.core.ports.outbound.FileReaderPort;
import es.ing.icenterprise.arthur.core.ports.outbound.PersistencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Component
public class DefaultJobProcessor implements JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobProcessor.class);

    private final List<FileReaderPort> fileReaders;
    private final PersistencePort persistencePort;
    private final ColumnAutoMapper columnAutoMapper;

    public DefaultJobProcessor(List<FileReaderPort> fileReaders,
                               PersistencePort persistencePort,
                               ColumnAutoMapper columnAutoMapper) {
        this.fileReaders = fileReaders;
        this.persistencePort = persistencePort;
        this.columnAutoMapper = columnAutoMapper;
    }

    @Override
    public void process(List<Job> jobs) {
        for (Job job : jobs) {
            if (job.getStatus() == Status.SKIPPED) {
                log.info("Skipping job: {}", job.getName());
                continue;
            }
            processJob(job);
        }
    }

    private void processJob(Job job) {
        job.start();
        log.info("Processing job: {}", job.getName());

        try {
            // Read data from file
            List<Action> data = readFileData(job);

            // Extract Excel headers from first row
            List<String> excelHeaders = data.isEmpty()
                    ? List.of()
                    : new ArrayList<>(data.get(0).data().keySet());

            // Process each task
            boolean hasFailure = false;
            for (Task task : job.getTasks()) {
                try {
                    processTask(task, data, excelHeaders);
                } catch (Exception e) {
                    hasFailure = true;
                    task.addLog(LogEntry.error(task.getName(), "Task failed: " + e.getMessage(), e));
                    task.complete(Status.FAILED);
                    if (task.isStopOnFailure()) {
                        job.addLog(LogEntry.error(job.getName(),
                                "Stopping job due to task failure: " + task.getName()));
                        break;
                    }
                }
            }

            Status jobStatus = determineJobStatus(job, hasFailure);
            job.complete(jobStatus);

        } catch (Exception e) {
            log.error("Job '{}' failed: {}", job.getName(), e.getMessage(), e);
            job.addLog(LogEntry.error(job.getName(), "Job failed: " + e.getMessage(), e));
            job.complete(Status.FAILED);
        }
    }

    private List<Action> readFileData(Job job) {
        FileReaderPort reader = fileReaders.stream()
                .filter(r -> r.supports(job.getFileType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No reader found for file type: " + job.getFileType()));

        List<Action> actions = new ArrayList<>();
        try (Stream<Map<String, Object>> stream = reader.read(Path.of(job.getFilePath()))) {
            stream.forEach(row -> actions.add(new Action(row)));
        }

        job.getMetrics().addCustomMetric("totalRowsRead", actions.size());
        job.addLog(LogEntry.info(job.getName(), "Read " + actions.size() + " rows from file"));
        return actions;
    }

    private void processTask(Task task, List<Action> data, List<String> excelHeaders) {
        task.start();
        log.info("Processing task: {} (type: {})", task.getName(), task.getTaskType());

        switch (task.getTaskType()) {
            case TRANSFORMATION -> processTransformationTask(task, data);
            case PERSISTENCE -> processPersistenceTask(task, data, excelHeaders);
        }

        if (task.getStatus() == Status.RUNNING) {
            task.complete(Status.SUCCESS);
        }
    }

    // ======================== TRANSFORMATION ========================

    private void processTransformationTask(Task task, List<Action> data) {
        for (Step step : task.getSteps()) {
            step.start();
            try {
                applyTransformation(step, data);
                step.getMetrics().incrementProcessed(data.size());
                step.complete(Status.SUCCESS);
            } catch (Exception e) {
                step.addLog(LogEntry.error(step.getName(), "Step failed: " + e.getMessage(), e));
                step.complete(Status.FAILED);
                throw e;
            }
        }
        task.getMetrics().incrementProcessed(data.size());
    }

    private void applyTransformation(Step step, List<Action> data) {
        log.debug("Applying transformation: {}", step.getStepType());

        switch (step.getStepType()) {
            case TRIM -> data.forEach(action ->
                    action.data().replaceAll((k, v) -> v instanceof String s ? s.trim() : v));
            case UPPERCASE -> data.forEach(action ->
                    action.data().replaceAll((k, v) -> v instanceof String s ? s.toUpperCase() : v));
            case CONCATENATE -> step.addLog(LogEntry.info(step.getName(),
                    "Concatenation applied based on step parameters"));
            default -> step.addLog(LogEntry.warn(step.getName(),
                    "Unknown transformation type: " + step.getStepType()));
        }
    }

    // ======================== PERSISTENCE ========================

    private void processPersistenceTask(Task task, List<Action> data, List<String> excelHeaders) {
        for (Step step : task.getSteps()) {
            step.start();
            try {
                executePersistenceStep(step, data, excelHeaders);
                step.complete(Status.SUCCESS);
            } catch (Exception e) {
                step.addLog(LogEntry.error(step.getName(), "Persistence step failed: " + e.getMessage(), e));
                step.complete(Status.FAILED);
                throw e;
            }
        }
    }

    private void executePersistenceStep(Step step, List<Action> data, List<String> excelHeaders) {
        log.debug("Executing persistence step: {}", step.getStepType());

        Map<String, Object> params = step.getParameters();

        switch (step.getStepType()) {
            case TRUNCATE -> {
                persistencePort.truncate(params);
                step.addLog(LogEntry.info(step.getName(), "Table truncated"));
            }
            case INSERT -> {
                // Resolve mappings: auto-map Excel headers → DB columns
                List<DatabaseMapping> mappings = resolveMappings(params, excelHeaders);

                persistencePort.insertData(data, mappings, params);
                step.getMetrics().incrementProcessed(data.size());
                step.addLog(LogEntry.info(step.getName(),
                        "Inserted " + data.size() + " records (" + mappings.size() + " columns mapped)"));
            }
            case SELECT -> {
                Object result = persistencePort.check(null, params);
                step.addLog(LogEntry.info(step.getName(), "Check completed"));
            }
            default -> step.addLog(LogEntry.warn(step.getName(),
                    "Unknown persistence type: " + step.getStepType()));
        }
    }

    /**
     * Resolves column mappings from step parameters.
     * If autoMap is enabled (default), uses ColumnAutoMapper to match Excel headers to DB columns.
     * Explicit mappings from YAML are always included and take priority.
     */
    @SuppressWarnings("unchecked")
    private List<DatabaseMapping> resolveMappings(Map<String, Object> params, List<String> excelHeaders) {
        String tableName = (String) params.getOrDefault("tableName", "ingesta_data");
        String schema = (String) params.get("schema");
        boolean autoMap = (boolean) params.getOrDefault("autoMap", true);

        // Parse explicit mappings from YAML parameters
        List<DatabaseMapping> explicitMappings = parseExplicitMappings(params);

        if (autoMap) {
            return columnAutoMapper.resolve(excelHeaders, tableName, schema, explicitMappings);
        }

        // autoMap disabled: use only explicit mappings
        return explicitMappings;
    }

    /**
     * Parses the "mappings" list from step parameters into DatabaseMapping objects.
     */
    @SuppressWarnings("unchecked")
    private List<DatabaseMapping> parseExplicitMappings(Map<String, Object> params) {
        Object rawMappings = params.get("mappings");
        if (rawMappings == null) return List.of();

        if (rawMappings instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> {
                        Map<String, Object> m = (Map<String, Object>) item;
                        String excelColumn = (String) m.get("excelColumn");
                        String dbColumn = (String) m.get("dbColumn");
                        String autoGenerate = (String) m.get("autoGenerate");
                        List<String> concatenate = (List<String>) m.get("concatenate");
                        String separator = (String) m.getOrDefault("separator", "");
                        return new DatabaseMapping(excelColumn, dbColumn, autoGenerate, concatenate, separator);
                    })
                    .toList();
        }

        return List.of();
    }

    private Status determineJobStatus(Job job, boolean hasFailure) {
        if (!hasFailure) return Status.SUCCESS;

        boolean allTasksFailed = job.getTasks().stream()
                .allMatch(t -> t.getStatus() == Status.FAILED);
        return allTasksFailed ? Status.FAILED : Status.PARTIAL;
    }
}
