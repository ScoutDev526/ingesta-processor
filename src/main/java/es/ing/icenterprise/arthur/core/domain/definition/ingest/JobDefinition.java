package es.ing.icenterprise.arthur.core.domain.definition.ingest;

import es.ing.icenterprise.arthur.core.domain.enums.FileType;

import java.util.List;
import java.util.Map;

public record JobDefinition(
    String name,
    String description,
    boolean enabled,
    FileSourceDefinition source,
    FileType fileType,
    List<TaskDefinition> tasks,
    Map<String, Object> parameters,
    int batchSize
) {
    public JobDefinition {
        if (batchSize <= 0) batchSize = 500;
        if (parameters == null) parameters = Map.of();
        if (tasks == null) tasks = List.of();
    }
}
