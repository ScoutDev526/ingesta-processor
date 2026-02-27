package es.ing.icenterprise.arthur.core.domain.factory;

import es.ing.icenterprise.arthur.core.domain.definition.ingest.*;
import es.ing.icenterprise.arthur.core.domain.factory.ingest.JobFactory;
import es.ing.icenterprise.arthur.core.domain.model.*;
import es.ing.icenterprise.arthur.core.domain.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobFactoryTest {

    private JobFactory jobFactory;

    @BeforeEach
    void setUp() {
        jobFactory = new JobFactory();
    }

    @Test
    @DisplayName("Should create a Job from JobDefinition with correct properties")
    void shouldCreateJobFromDefinition() throws IOException {
        // Given
        Path tempFile = Files.createTempFile("test-data", ".xlsx");
        tempFile.toFile().deleteOnExit();

        JobDefinition definition = createSampleJobDefinition();

        // When
        Job job = jobFactory.createJob(definition, tempFile);

        // Then
        assertThat(job.getName()).isEqualTo("test-job");
        assertThat(job.getFileType()).isEqualTo(FileType.EXCEL);
        assertThat(job.getFilePath()).isEqualTo(tempFile.toString());
        assertThat(job.getStatus()).isEqualTo(Status.PENDING);
        assertThat(job.getTasks()).hasSize(2);
    }

    @Test
    @DisplayName("Should create Tasks with correct types and steps")
    void shouldCreateTasksWithCorrectTypes() throws IOException {
        Path tempFile = Files.createTempFile("test-data", ".xlsx");
        tempFile.toFile().deleteOnExit();

        JobDefinition definition = createSampleJobDefinition();
        Job job = jobFactory.createJob(definition, tempFile);

        Task transformationTask = job.getTasks().get(0);
        assertThat(transformationTask.getName()).isEqualTo("transform");
        assertThat(transformationTask.getTaskType()).isEqualTo(TaskType.TRANSFORMATION);
        assertThat(transformationTask.getSteps()).hasSize(2);

        Task persistenceTask = job.getTasks().get(1);
        assertThat(persistenceTask.getName()).isEqualTo("persist");
        assertThat(persistenceTask.getTaskType()).isEqualTo(TaskType.PERSISTENCE);
        assertThat(persistenceTask.getSteps()).hasSize(1);
    }

    @Test
    @DisplayName("Should create Steps with correct StepType")
    void shouldCreateStepsWithCorrectType() throws IOException {
        Path tempFile = Files.createTempFile("test-data", ".xlsx");
        tempFile.toFile().deleteOnExit();

        JobDefinition definition = createSampleJobDefinition();
        Job job = jobFactory.createJob(definition, tempFile);

        Task task = job.getTasks().get(0);
        assertThat(task.getSteps().get(0).getStepType()).isEqualTo(StepType.TRIM);
        assertThat(task.getSteps().get(1).getStepType()).isEqualTo(StepType.UPPERCASE);
    }

    @Test
    @DisplayName("canLoadFile should return true for existing readable files")
    void canLoadFileShouldReturnTrueForExistingFile() throws IOException {
        Path tempFile = Files.createTempFile("test", ".xlsx");
        tempFile.toFile().deleteOnExit();

        assertThat(jobFactory.canLoadFile(tempFile)).isTrue();
    }

    @Test
    @DisplayName("canLoadFile should return false for null path")
    void canLoadFileShouldReturnFalseForNull() {
        assertThat(jobFactory.canLoadFile(null)).isFalse();
    }

    @Test
    @DisplayName("canLoadFile should return false for non-existent file")
    void canLoadFileShouldReturnFalseForNonExistent() {
        assertThat(jobFactory.canLoadFile(Path.of("/nonexistent/file.xlsx"))).isFalse();
    }

    private JobDefinition createSampleJobDefinition() {
        StepDefinition trimStep = new StepDefinition("trim-step", 1, "TRIM", Map.of());
        StepDefinition upperStep = new StepDefinition("upper-step", 2, "UPPERCASE", Map.of());
        StepDefinition insertStep = new StepDefinition("insert-step", 1, "INSERT", Map.of());

        TaskDefinition transformTask = new TaskDefinition(
                "transform", 1, "TRANSFORMATION", true,
                List.of(trimStep, upperStep), Map.of());

        TaskDefinition persistTask = new TaskDefinition(
                "persist", 2, "PERSISTENCE", true,
                List.of(insertStep), Map.of());

        FileSourceDefinition source = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("/data/test.xlsx"),
                "/data/processed/");

        return new JobDefinition(
                "test-job", "Test job description", true,
                source, FileType.EXCEL,
                List.of(transformTask, persistTask),
                Map.of(), 500);
    }
}
