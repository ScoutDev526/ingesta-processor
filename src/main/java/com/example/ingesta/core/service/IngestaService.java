package com.example.ingesta.core.service;

import com.example.ingesta.core.domain.definition.JobDefinition;
import com.example.ingesta.core.domain.factory.JobFactory;
import com.example.ingesta.core.domain.model.*;
import com.example.ingesta.core.port.inbound.ExecuteCommand;
import com.example.ingesta.core.port.inbound.ExecuteProcessUseCase;
import com.example.ingesta.core.port.outbound.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class IngestaService implements ExecuteProcessUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestaService.class);

    private final YamlScannerPort yamlScanner;
    private final JobDefinitionLoaderPort jobDefinitionLoader;
    private final List<FileDownloaderPort> fileDownloaders;
    private final JobFactory jobFactory;
    private final JobProcessor jobProcessor;
    private final MetricsCollector metricsCollector;
    private final NotificationPort notificationPort;
    private final CleanupWorkingDirectoryPort cleanupPort;

    public IngestaService(YamlScannerPort yamlScanner,
                          JobDefinitionLoaderPort jobDefinitionLoader,
                          List<FileDownloaderPort> fileDownloaders,
                          JobFactory jobFactory,
                          JobProcessor jobProcessor,
                          MetricsCollector metricsCollector,
                          NotificationPort notificationPort,
                          CleanupWorkingDirectoryPort cleanupPort) {
        this.yamlScanner = yamlScanner;
        this.jobDefinitionLoader = jobDefinitionLoader;
        this.fileDownloaders = fileDownloaders;
        this.jobFactory = jobFactory;
        this.jobProcessor = jobProcessor;
        this.metricsCollector = metricsCollector;
        this.notificationPort = notificationPort;
        this.cleanupPort = cleanupPort;
    }

    @Override
    public ProcessReport execute(ExecuteCommand command) {
        log.info("Starting ingestion process. Manual: {}, Filter: {}",
                command.manuallyTriggered(), command.jobFilter());

        List<Job> jobs = new ArrayList<>();

        try {
            // 1. Scan for YAML job definitions
            List<Path> yamlPaths = yamlScanner.scanJobDefinitions();
            log.info("Found {} job definition(s)", yamlPaths.size());

            // 2. Load and filter job definitions
            List<JobDefinition> definitions = yamlPaths.stream()
                    .map(jobDefinitionLoader::load)
                    .filter(JobDefinition::enabled)
                    .filter(def -> command.shouldRunAll() || command.jobFilter().contains(def.name()))
                    .toList();

            log.info("Loaded {} enabled job definition(s)", definitions.size());

            // 3. For each definition: download file → create job
            for (JobDefinition definition : definitions) {
                try {
                    Path dataFilePath = downloadFile(definition);

                    if (jobFactory.canLoadFile(dataFilePath)) {
                        Job job = jobFactory.createJob(definition, dataFilePath);
                        jobs.add(job);
                    } else {
                        Job skippedJob = new Job(definition.name(), "N/A", definition.fileType());
                        skippedJob.skip("Data file could not be loaded: " + dataFilePath);
                        jobs.add(skippedJob);
                    }
                } catch (Exception e) {
                    log.error("Failed to prepare job '{}': {}", definition.name(), e.getMessage());
                    Job failedJob = new Job(definition.name(), "N/A", definition.fileType());
                    failedJob.addLog(LogEntry.error(definition.name(),
                            "Failed to prepare job: " + e.getMessage(), e));
                    failedJob.complete(Status.FAILED);
                    jobs.add(failedJob);
                }
            }

            // 4. Process all jobs
            jobProcessor.process(jobs);

        } catch (Exception e) {
            log.error("Ingestion process failed: {}", e.getMessage(), e);
        }

        // 5. Collect metrics and build report
        ProcessReport report = metricsCollector.collect(jobs, command.manuallyTriggered());

        // 6. Send notification
        try {
            notificationPort.notify(report, NotificationType.EMAIL);
        } catch (Exception e) {
            log.warn("Failed to send notification: {}", e.getMessage());
        }

        // 7. Cleanup working directory
        try {
            for (Job job : jobs) {
                if (job.getFilePath() != null && !"N/A".equals(job.getFilePath())) {
                    cleanupPort.delete(Path.of(job.getFilePath()));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup working directory: {}", e.getMessage());
        }

        log.info("Ingestion process completed. Status: {}, Duration: {}ms",
                report.getStatus(), report.getTotalDurationMs());

        return report;
    }

    private Path downloadFile(JobDefinition definition) {
        FileDownloaderPort downloader = fileDownloaders.stream()
                .filter(d -> d.supports(definition.source().type()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No downloader found for source type: " + definition.source().type()));

        return downloader.download(definition.source());
    }
}
