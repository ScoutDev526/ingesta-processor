package com.example.ingesta.core.port.outbound;

import com.example.ingesta.core.domain.definition.JobDefinition;

import java.nio.file.Path;

/**
 * Parses a single YAML file into a JobDefinition.
 */
public interface JobDefinitionLoaderPort {
    JobDefinition load(Path yamlFilePath);
}
