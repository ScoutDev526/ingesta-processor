package com.example.ingesta.core.service;

import com.example.ingesta.core.domain.definition.*;
import com.example.ingesta.core.domain.factory.JobFactory;
import com.example.ingesta.core.domain.model.*;
import com.example.ingesta.core.port.inbound.ExecuteCommand;
import com.example.ingesta.core.port.outbound.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestaServiceTest {

    @Mock private YamlScannerPort yamlScanner;
    @Mock private JobDefinitionLoaderPort jobDefinitionLoader;
    @Mock private FileDownloaderPort fileDownloader;
    @Mock private JobProcessor jobProcessor;
    @Mock private MetricsCollector metricsCollector;
    @Mock private NotificationPort notificationPort;
    @Mock private CleanupWorkingDirectoryPort cleanupPort;

    private JobFactory jobFactory;
    private IngestaService service;

    @BeforeEach
    void setUp() {
        jobFactory = new JobFactory();
        when(fileDownloader.supports(any())).thenReturn(true);

        service = new IngestaService(
                yamlScanner,
                jobDefinitionLoader,
                List.of(fileDownloader),
                jobFactory,
                jobProcessor,
                metricsCollector,
                notificationPort,
                cleanupPort
        );
    }

    @Test
    @DisplayName("Should execute full pipeline and return ProcessReport")
    void shouldExecuteFullPipeline() throws IOException {
        // Given
        Path tempYaml = Files.createTempFile("job-def", ".yml");
        Path tempData = Files.createTempFile("data", ".xlsx");
        tempYaml.toFile().deleteOnExit();
        tempData.toFile().deleteOnExit();

        JobDefinition definition = createSampleDefinition();
        ProcessReport expectedReport = createSuccessReport();

        when(yamlScanner.scanJobDefinitions()).thenReturn(List.of(tempYaml));
        when(jobDefinitionLoader.load(tempYaml)).thenReturn(definition);
        when(fileDownloader.download(any())).thenReturn(tempData);
        when(metricsCollector.collect(anyList(), anyBoolean())).thenReturn(expectedReport);

        // When
        ProcessReport report = service.execute(ExecuteCommand.fromManual());

        // Then
        assertThat(report).isNotNull();
        assertThat(report.getStatus()).isEqualTo(Status.SUCCESS);

        verify(yamlScanner).scanJobDefinitions();
        verify(jobDefinitionLoader).load(tempYaml);
        verify(fileDownloader).download(any());
        verify(jobProcessor).process(anyList());
        verify(metricsCollector).collect(anyList(), eq(true));
        verify(notificationPort).notify(any(), eq(NotificationType.EMAIL));
    }

    @Test
    @DisplayName("Should filter disabled jobs")
    void shouldFilterDisabledJobs() throws IOException {
        Path tempYaml = Files.createTempFile("job-def", ".yml");
        tempYaml.toFile().deleteOnExit();

        JobDefinition disabledDef = new JobDefinition(
                "disabled-job", "Disabled", false,
                createSource(), FileType.EXCEL, List.of(), Map.of(), 500);

        ProcessReport emptyReport = ProcessReport.builder()
                .executionStart(java.time.Instant.now())
                .executionEnd(java.time.Instant.now())
                .status(Status.SUCCESS)
                .totals(AggregatedMetrics.fromJobs(List.of()))
                .build();

        when(yamlScanner.scanJobDefinitions()).thenReturn(List.of(tempYaml));
        when(jobDefinitionLoader.load(tempYaml)).thenReturn(disabledDef);
        when(metricsCollector.collect(anyList(), anyBoolean())).thenReturn(emptyReport);

        // When
        ProcessReport report = service.execute(ExecuteCommand.fromScheduler());

        // Then
        verify(fileDownloader, never()).download(any());
        verify(jobProcessor).process(argThat(List::isEmpty));
    }

    @Test
    @DisplayName("Should filter jobs by name when jobFilter is provided")
    void shouldFilterJobsByName() throws IOException {
        Path tempYaml1 = Files.createTempFile("job1", ".yml");
        Path tempYaml2 = Files.createTempFile("job2", ".yml");
        tempYaml1.toFile().deleteOnExit();
        tempYaml2.toFile().deleteOnExit();
        Path tempData = Files.createTempFile("data", ".xlsx");
        tempData.toFile().deleteOnExit();

        JobDefinition job1 = createDefinitionWithName("job-alpha");
        JobDefinition job2 = createDefinitionWithName("job-beta");

        ProcessReport report = createSuccessReport();

        when(yamlScanner.scanJobDefinitions()).thenReturn(List.of(tempYaml1, tempYaml2));
        when(jobDefinitionLoader.load(tempYaml1)).thenReturn(job1);
        when(jobDefinitionLoader.load(tempYaml2)).thenReturn(job2);
        when(fileDownloader.download(any())).thenReturn(tempData);
        when(metricsCollector.collect(anyList(), anyBoolean())).thenReturn(report);

        // Execute with filter for only job-alpha
        service.execute(ExecuteCommand.fromManual(List.of("job-alpha")));

        // Should only download file for one job
        verify(fileDownloader, times(1)).download(any());
    }

    @Test
    @DisplayName("Should handle download failure gracefully")
    void shouldHandleDownloadFailure() throws IOException {
        Path tempYaml = Files.createTempFile("job-def", ".yml");
        tempYaml.toFile().deleteOnExit();

        JobDefinition definition = createSampleDefinition();
        ProcessReport report = ProcessReport.builder()
                .executionStart(java.time.Instant.now())
                .executionEnd(java.time.Instant.now())
                .status(Status.FAILED)
                .totals(AggregatedMetrics.fromJobs(List.of()))
                .build();

        when(yamlScanner.scanJobDefinitions()).thenReturn(List.of(tempYaml));
        when(jobDefinitionLoader.load(tempYaml)).thenReturn(definition);
        when(fileDownloader.download(any())).thenThrow(new RuntimeException("Download failed"));
        when(metricsCollector.collect(anyList(), anyBoolean())).thenReturn(report);

        // Should not throw
        ProcessReport result = service.execute(ExecuteCommand.fromManual());
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("Should continue if notification fails")
    void shouldContinueIfNotificationFails() throws IOException {
        Path tempYaml = Files.createTempFile("job-def", ".yml");
        Path tempData = Files.createTempFile("data", ".xlsx");
        tempYaml.toFile().deleteOnExit();
        tempData.toFile().deleteOnExit();

        when(yamlScanner.scanJobDefinitions()).thenReturn(List.of(tempYaml));
        when(jobDefinitionLoader.load(any())).thenReturn(createSampleDefinition());
        when(fileDownloader.download(any())).thenReturn(tempData);
        when(metricsCollector.collect(anyList(), anyBoolean())).thenReturn(createSuccessReport());
        doThrow(new RuntimeException("Email server down")).when(notificationPort).notify(any(), any());

        // Should not throw
        ProcessReport result = service.execute(ExecuteCommand.fromManual());
        assertThat(result).isNotNull();
    }

    // === Helpers ===

    private JobDefinition createSampleDefinition() {
        return new JobDefinition(
                "test-job", "Test", true,
                createSource(), FileType.EXCEL, List.of(), Map.of(), 500);
    }

    private JobDefinition createDefinitionWithName(String name) {
        return new JobDefinition(
                name, "Description for " + name, true,
                createSource(), FileType.EXCEL, List.of(), Map.of(), 500);
    }

    private FileSourceDefinition createSource() {
        return new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("/data/test.xlsx"),
                "");
    }

    private ProcessReport createSuccessReport() {
        return ProcessReport.builder()
                .executionStart(java.time.Instant.now())
                .executionEnd(java.time.Instant.now())
                .status(Status.SUCCESS)
                .manuallyTriggered(true)
                .totals(AggregatedMetrics.fromJobs(List.of()))
                .build();
    }
}
