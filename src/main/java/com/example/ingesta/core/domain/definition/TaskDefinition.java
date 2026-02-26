package com.example.ingesta.core.domain.definition;

import java.util.List;
import java.util.Map;

public record TaskDefinition(
    String name,
    int order,
    String type,
    boolean stopOnFailure,
    List<StepDefinition> subtasks,
    Map<String, Object> parameters
) {
    public TaskDefinition(String name, int order, String type, boolean stopOnFailure, List<StepDefinition> subtasks) {
        this(name, order, type, stopOnFailure, subtasks, Map.of());
    }
}
