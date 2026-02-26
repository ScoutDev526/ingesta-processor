package com.example.ingesta.core.domain.definition;

import java.util.Map;

public record FileSourceLocationDefinition(
    String path,
    Map<String, String> properties
) {
    public FileSourceLocationDefinition(String path) {
        this(path, Map.of());
    }
}
