package es.ing.icenterprise.arthur.adapters.outbound.yaml;

import es.ing.icenterprise.arthur.core.domain.definition.ingest.*;
import es.ing.icenterprise.arthur.core.domain.enums.FileType;
import es.ing.icenterprise.arthur.core.ports.outbound.JobDefinitionLoaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Component
public class SnakeYamlJobDefinitionAdapter implements JobDefinitionLoaderPort {

    private static final Logger log = LoggerFactory.getLogger(SnakeYamlJobDefinitionAdapter.class);

    @Override
    @SuppressWarnings("unchecked")
    public JobDefinition load(Path yamlFilePath) {
        log.info("Loading job definition from: {}", yamlFilePath);

        try (InputStream is = Files.newInputStream(yamlFilePath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);

            return mapToJobDefinition(data);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load YAML: " + yamlFilePath, e);
        }
    }

    @SuppressWarnings("unchecked")
    private JobDefinition mapToJobDefinition(Map<String, Object> data) {
        String name = (String) data.get("name");
        String description = (String) data.getOrDefault("description", "");
        boolean enabled = (boolean) data.getOrDefault("enabled", true);
        FileType fileType = FileType.valueOf(((String) data.get("fileType")).toUpperCase());
        int batchSize = (int) data.getOrDefault("batchSize", 500);
        Map<String, Object> parameters = (Map<String, Object>) data.getOrDefault("parameters", Map.of());

        // Source
        Map<String, Object> sourceMap = (Map<String, Object>) data.get("source");
        FileSourceDefinition source = mapToFileSource(sourceMap);

        // Tasks
        List<Map<String, Object>> tasksList = (List<Map<String, Object>>) data.getOrDefault("tasks", List.of());
        List<TaskDefinition> tasks = tasksList.stream()
                .map(this::mapToTaskDefinition)
                .toList();

        return new JobDefinition(name, description, enabled, source, fileType, tasks, parameters, batchSize);
    }

    @SuppressWarnings("unchecked")
    private FileSourceDefinition mapToFileSource(Map<String, Object> data) {
        if (data == null) throw new IllegalArgumentException("Source definition is required");

        FileSourceType type = FileSourceType.valueOf(((String) data.get("type")).toUpperCase());
        Map<String, Object> locationMap = (Map<String, Object>) data.getOrDefault("location", Map.of());
        String path = (String) locationMap.getOrDefault("path", "");
        String afterProcessing = (String) data.getOrDefault("locationAfterProcessing", "");

        return new FileSourceDefinition(type, new FileSourceLocationDefinition(path), afterProcessing);
    }

    @SuppressWarnings("unchecked")
    private TaskDefinition mapToTaskDefinition(Map<String, Object> data) {
        String name = (String) data.get("name");
        int order = (int) data.getOrDefault("order", 0);
        String type = (String) data.get("type");
        boolean stopOnFailure = (boolean) data.getOrDefault("stopOnFailure", false);
        Map<String, Object> parameters = (Map<String, Object>) data.getOrDefault("parameters", Map.of());

        List<Map<String, Object>> subtasksList =
                (List<Map<String, Object>>) data.getOrDefault("subtasks", List.of());
        List<StepDefinition> subtasks = subtasksList.stream()
                .map(this::mapToStepDefinition)
                .toList();

        return new TaskDefinition(name, order, type, stopOnFailure, subtasks, parameters);
    }

    @SuppressWarnings("unchecked")
    private StepDefinition mapToStepDefinition(Map<String, Object> data) {
        String name = (String) data.get("name");
        int order = (int) data.getOrDefault("order", 0);
        String type = (String) data.get("type");
        Map<String, Object> parameters = (Map<String, Object>) data.getOrDefault("parameters", Map.of());

        return new StepDefinition(name, order, type, parameters);
    }
}
