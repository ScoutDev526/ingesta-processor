package com.example.ingesta.core.domain.factory;

import com.example.ingesta.core.domain.definition.JobDefinition;
import com.example.ingesta.core.domain.definition.StepDefinition;
import com.example.ingesta.core.domain.definition.TaskDefinition;
import com.example.ingesta.core.domain.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

@Component
public class JobFactory {

    private static final Logger log = LoggerFactory.getLogger(JobFactory.class);

    public Job createJob(JobDefinition definition, Path dataFilePath) {
        log.info("Creating job '{}' from file: {}", definition.name(), dataFilePath);

        Job job = new Job(
                definition.name(),
                dataFilePath.toString(),
                definition.fileType()
        );

        for (TaskDefinition taskDef : definition.tasks()) {
            Task task = createTask(taskDef, definition.parameters());
            job.addTask(task);
        }

        return job;
    }

    public Task createTask(TaskDefinition definition, Map<String, Object> jobParams) {
        TaskType taskType = TaskType.valueOf(definition.type().toUpperCase());

        Task task = new Task(
                definition.name(),
                taskType,
                definition.order(),
                definition.stopOnFailure()
        );

        if (definition.subtasks() != null) {
            for (StepDefinition stepDef : definition.subtasks()) {
                Step step = createStep(stepDef, jobParams, definition.parameters());
                task.addStep(step);
            }
        }

        return task;
    }

    public Step createStep(StepDefinition definition, Map<String, Object> jobParams,
                           Map<String, Object> taskParams) {
        StepType stepType = StepType.valueOf(definition.type().toUpperCase());

        return new Step(
                definition.name(),
                stepType,
                definition.order()
        );
    }

    public boolean canLoadFile(Path dataFilePath) {
        if (dataFilePath == null) return false;
        return dataFilePath.toFile().exists() && dataFilePath.toFile().canRead();
    }
}
