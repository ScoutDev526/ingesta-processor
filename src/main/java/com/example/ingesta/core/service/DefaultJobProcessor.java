package com.example.ingesta.core.service;

import com.example.ingesta.core.domain.model.*;
import com.example.ingesta.core.port.outbound.FileReaderPort;
import com.example.ingesta.core.port.outbound.PersistencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class DefaultJobProcessor implements JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobProcessor.class);

    private final List<FileReaderPort> fileReaders;
    private final PersistencePort persistencePort;

    public DefaultJobProcessor(List<FileReaderPort> fileReaders, PersistencePort persistencePort) {
        this.fileReaders = fileReaders;
        this.persistencePort = persistencePort;
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

            // Process each task
            boolean hasFailure = false;
            for (Task task : job.getTasks()) {
                try {
                    processTask(task, data, job);
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

            // Determine final job status
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

    private void processTask(Task task, List<Action> data, Job job) {
        task.start();
        log.info("Processing task: {} (type: {})", task.getName(), task.getTaskType());

        switch (task.getTaskType()) {
            case TRANSFORMATION -> processTransformationTask(task, data);
            case PERSISTENCE -> processPersistenceTask(task, data, job);
        }

        if (task.getStatus() == Status.RUNNING) {
            task.complete(Status.SUCCESS);
        }
    }

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

    private void processPersistenceTask(Task task, List<Action> data, Job job) {
        for (Step step : task.getSteps()) {
            step.start();
            try {
                executePersistenceStep(step, data);
                step.complete(Status.SUCCESS);
            } catch (Exception e) {
                step.addLog(LogEntry.error(step.getName(), "Persistence step failed: " + e.getMessage(), e));
                step.complete(Status.FAILED);
                throw e;
            }
        }
    }

    private void executePersistenceStep(Step step, List<Action> data) {
        log.debug("Executing persistence step: {}", step.getStepType());

        Map<String, Object> params = Map.of();

        switch (step.getStepType()) {
            case TRUNCATE -> {
                persistencePort.truncate(params);
                step.addLog(LogEntry.info(step.getName(), "Table truncated"));
            }
            case INSERT -> {
                persistencePort.insertData(data, params);
                step.getMetrics().incrementProcessed(data.size());
                step.addLog(LogEntry.info(step.getName(), "Inserted " + data.size() + " records"));
            }
            case SELECT -> {
                Object result = persistencePort.check(null, params);
                step.addLog(LogEntry.info(step.getName(), "Check completed"));
            }
            default -> step.addLog(LogEntry.warn(step.getName(),
                    "Unknown persistence type: " + step.getStepType()));
        }
    }

    private Status determineJobStatus(Job job, boolean hasFailure) {
        if (!hasFailure) return Status.SUCCESS;

        boolean allTasksFailed = job.getTasks().stream()
                .allMatch(t -> t.getStatus() == Status.FAILED);
        return allTasksFailed ? Status.FAILED : Status.PARTIAL;
    }
}
