package com.example.ingesta.adapter.outbound.yaml;

import com.example.ingesta.core.domain.definition.FileSourceType;
import com.example.ingesta.core.domain.definition.JobDefinition;
import com.example.ingesta.core.domain.model.FileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnakeYamlJobDefinitionAdapterTest {

    private SnakeYamlJobDefinitionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SnakeYamlJobDefinitionAdapter();
    }

    @Test
    @DisplayName("Should load a complete job definition from YAML")
    void shouldLoadCompleteJobDefinition() throws IOException {
        String yaml = """
                name: test-import
                description: Test data import
                enabled: true
                fileType: EXCEL
                batchSize: 1000
                source:
                  type: RESOURCES
                  location:
                    path: /data/test.xlsx
                  locationAfterProcessing: /data/processed/
                parameters:
                  tableName: test_table
                tasks:
                  - name: clean-data
                    order: 1
                    type: TRANSFORMATION
                    stopOnFailure: true
                    subtasks:
                      - name: trim-step
                        order: 1
                        type: TRIM
                      - name: upper-step
                        order: 2
                        type: UPPERCASE
                  - name: persist
                    order: 2
                    type: PERSISTENCE
                    stopOnFailure: false
                    subtasks:
                      - name: insert-data
                        order: 1
                        type: INSERT
                        parameters:
                          tableName: test_table
                """;

        Path tempFile = Files.createTempFile("test-job", ".yml");
        Files.writeString(tempFile, yaml);
        tempFile.toFile().deleteOnExit();

        // When
        JobDefinition definition = adapter.load(tempFile);

        // Then
        assertThat(definition.name()).isEqualTo("test-import");
        assertThat(definition.description()).isEqualTo("Test data import");
        assertThat(definition.enabled()).isTrue();
        assertThat(definition.fileType()).isEqualTo(FileType.EXCEL);
        assertThat(definition.batchSize()).isEqualTo(1000);
        assertThat(definition.source().type()).isEqualTo(FileSourceType.RESOURCES);
        assertThat(definition.source().location().path()).isEqualTo("/data/test.xlsx");
        assertThat(definition.tasks()).hasSize(2);

        // First task
        assertThat(definition.tasks().get(0).name()).isEqualTo("clean-data");
        assertThat(definition.tasks().get(0).type()).isEqualTo("TRANSFORMATION");
        assertThat(definition.tasks().get(0).stopOnFailure()).isTrue();
        assertThat(definition.tasks().get(0).subtasks()).hasSize(2);

        // Second task
        assertThat(definition.tasks().get(1).name()).isEqualTo("persist");
        assertThat(definition.tasks().get(1).type()).isEqualTo("PERSISTENCE");
        assertThat(definition.tasks().get(1).subtasks()).hasSize(1);
    }

    @Test
    @DisplayName("Should use default values for optional fields")
    void shouldUseDefaults() throws IOException {
        String yaml = """
                name: minimal-job
                fileType: XML
                source:
                  type: RESOURCES
                  location:
                    path: /data/test.xml
                """;

        Path tempFile = Files.createTempFile("minimal", ".yml");
        Files.writeString(tempFile, yaml);
        tempFile.toFile().deleteOnExit();

        JobDefinition definition = adapter.load(tempFile);

        assertThat(definition.name()).isEqualTo("minimal-job");
        assertThat(definition.enabled()).isTrue();
        assertThat(definition.batchSize()).isEqualTo(500);
        assertThat(definition.tasks()).isEmpty();
    }

    @Test
    @DisplayName("Should throw for non-existent file")
    void shouldThrowForNonExistentFile() {
        Path nonExistent = Path.of("/nonexistent/job.yml");

        assertThatThrownBy(() -> adapter.load(nonExistent))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to load YAML");
    }
}
