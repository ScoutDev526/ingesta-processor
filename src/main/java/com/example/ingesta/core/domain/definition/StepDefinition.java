package com.example.ingesta.core.domain.definition;

import java.util.Map;

public record StepDefinition(
    String name,
    int order,
    String type,
    Map<String, Object> parameters
) {
    public StepDefinition(String name, int order, String type) {
        this(name, order, type, Map.of());
    }
}
