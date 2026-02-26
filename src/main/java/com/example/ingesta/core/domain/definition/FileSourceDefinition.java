package com.example.ingesta.core.domain.definition;

public record FileSourceDefinition(
    FileSourceType type,
    FileSourceLocationDefinition location,
    String locationAfterProcessing
) {}
