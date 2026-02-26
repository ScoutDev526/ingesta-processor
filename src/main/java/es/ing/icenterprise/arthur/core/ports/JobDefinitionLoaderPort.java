package es.ing.icenterprise.arthur.core.ports;

import es.ing.icenterprise.arthur.core.domain.definition.ingest.JobDefinition;

import java.nio.file.Path;

/**
 * Parses a single YAML file into a JobDefinition.
 */
public interface JobDefinitionLoaderPort {
    JobDefinition load(Path yamlFilePath);
}
