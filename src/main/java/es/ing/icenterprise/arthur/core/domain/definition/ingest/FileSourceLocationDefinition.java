package es.ing.icenterprise.arthur.core.domain.definition.ingest;

import java.util.Map;

public record FileSourceLocationDefinition(
    String path,
    Map<String, String> properties
) {
    public FileSourceLocationDefinition(String path) {
        this(path, Map.of());
    }
}
