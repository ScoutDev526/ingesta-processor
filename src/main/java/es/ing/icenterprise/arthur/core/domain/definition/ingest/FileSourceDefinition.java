package es.ing.icenterprise.arthur.core.domain.definition.ingest;

public record FileSourceDefinition(
    FileSourceType type,
    FileSourceLocationDefinition location,
    String locationAfterProcessing
) {}
