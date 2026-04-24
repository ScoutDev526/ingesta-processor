package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.adapters.outbound.report.ExcelReportStore;
import es.ing.icenterprise.arthur.core.domain.definition.ingest.JobDefinition;
import es.ing.icenterprise.arthur.core.domain.factory.ingest.JobFactory;
import es.ing.icenterprise.arthur.core.domain.model.*;
import es.ing.icenterprise.arthur.core.domain.enums.*;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteCommand;
import es.ing.icenterprise.arthur.core.ports.inbound.ExecuteProcessUseCase;
import es.ing.icenterprise.arthur.core.ports.inbound.ExtractDataHrUseCase;
import es.ing.icenterprise.arthur.core.ports.outbound.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class IngestaService implements ExecuteProcessUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestaService.class);

    @Value("${ingesta.parallel-jobs:1}")
    private int parallelJobs;

    @Value("${ingesta.working-directory:/tmp/ingesta}")
    private String workingDirectory;

    @Value("${ingesta.report.title:ESClassificationSystem}")
    private String reportTitle;

    private final YamlScannerPort yamlScanner;
    private final JobDefinitionLoaderPort jobDefinitionLoader;
    private final List<FileDownloaderPort> fileDownloaders;
    private final JobFactory jobFactory;
    private final JobProcessor jobProcessor;
    private final MetricsCollector metricsCollector;
    private final NotificationPort notificationPort;
    private final CleanupWorkingDirectoryPort cleanupPort;
    private final ArchiveProcessedFilePort archivePort;
    private final ExecutionLogExporterPort executionLogExporter;
    private final ExcelReportStore excelReportStore;
    private final ExtractDataHrUseCase extractDataHrUseCase;

    public IngestaService(YamlScannerPort yamlScanner,
                          JobDefinitionLoaderPort jobDefinitionLoader,
                          List<FileDownloaderPort> fileDownloaders,
                          JobFactory jobFactory,
                          JobProcessor jobProcessor,
                          MetricsCollector metricsCollector,
                          NotificationPort notificationPort,
                          CleanupWorkingDirectoryPort cleanupPort,
                          ArchiveProcessedFilePort archivePort,
                          ExecutionLogExporterPort executionLogExporter,
                          ExcelReportStore excelReportStore,
                          ExtractDataHrUseCase extractDataHrUseCase) {
        this.yamlScanner = yamlScanner;
        this.jobDefinitionLoader = jobDefinitionLoader;
        this.fileDownloaders = fileDownloaders;
        this.jobFactory = jobFactory;
        this.jobProcessor = jobProcessor;
        this.metricsCollector = metricsCollector;
        this.notificationPort = notificationPort;
        this.cleanupPort = cleanupPort;
        this.archivePort = archivePort;
        this.executionLogExporter = executionLogExporter;
        this.excelReportStore = excelReportStore;
        this.extractDataHrUseCase = extractDataHrUseCase;
    }

    @Override
    public ProcessReport execute(ExecuteCommand command) {
        log.info("Starting ingestion process. Manual: {}, Filter: {}",
                command.manuallyTriggered(), command.jobFilter());

        List<Job> jobs = new ArrayList<>();

        // 0. Populate HR table from LDAP (Active Directory) before any YAML job runs.
        //    The HR table feeds DepartmentUpdateService and RoleOwnershipService, so it
        //    must be refreshed first. A failure here is logged as a FAILED synthetic job
        //    but does not abort the rest of the pipeline. It is kept out of the list
        //    passed to jobProcessor so the processor does not try to re-run it.
        Job ldapHrJob = runLdapHrImport();

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

            // 4. Process all jobs (in parallel if ingesta.parallel-jobs > 1)
            if (parallelJobs > 1 && jobs.size() > 1) {
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelJobs, jobs.size()));
                try {
                    List<CompletableFuture<Void>> futures = jobs.stream()
                            .map(job -> CompletableFuture.runAsync(
                                    () -> jobProcessor.process(List.of(job)), pool))
                            .toList();
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } finally {
                    pool.shutdown();
                }
            } else {
                jobProcessor.process(List.copyOf(jobs));
            }

        } catch (Exception e) {
            log.error("Ingestion process failed: {}", e.getMessage(), e);
        }

        // 4b. Add the LDAP HR pre-ingestion job to the list so it appears in the
        //     report alongside the YAML jobs.
        jobs.add(0, ldapHrJob);

        // 5. Collect metrics and build report
        ProcessReport report = metricsCollector.collect(jobs, command.manuallyTriggered());

        // 5b. Generate Excel log report and store it
        try {
            byte[] excel = executionLogExporter.export(jobs, reportTitle);
            excelReportStore.save(report.getId(), excel);

            String filename = "ingesta-report-" + LocalDate.now() + ".xlsx";
            Path reportPath = Path.of(workingDirectory, filename);
            Files.createDirectories(reportPath.getParent());
            Files.write(reportPath, excel);
            log.info("Excel report saved to: {}", reportPath);
        } catch (Exception e) {
            log.warn("Failed to generate Excel report: {}", e.getMessage());
        }

        // 6. Send notification
        try {
            notificationPort.notify(report, NotificationType.EMAIL);
        } catch (Exception e) {
            log.warn("Failed to send notification: {}", e.getMessage());
        }

        // 7. Post-process each data file: XLSX are archived into the cached dir,
        //    anything else is deleted from the working directory.
        try {
            for (Job job : jobs) {
                String filePath = job.getFilePath();
                if (filePath == null || "N/A".equals(filePath)) {
                    continue;
                }
                Path path = Path.of(filePath);
                if (path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
                    archivePort.archive(path);
                } else {
                    cleanupPort.delete(path);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to post-process working directory: {}", e.getMessage());
        }

        log.info("Ingestion process completed. Status: {}, Duration: {}ms",
                report.getStatus(), report.getTotalDurationMs());

        return report;
    }

    private Job runLdapHrImport() {
        Job ldapJob = new Job("ldap-hr-import", "N/A", FileType.EXCEL);
        ldapJob.start();
        try {
            log.info("Running LDAP HR pre-ingestion step");
            List<PersonLdap> imported = extractDataHrUseCase.extractDataHr();
            ldapJob.getMetrics().incrementProcessed(imported.size());
            ldapJob.addLog(LogEntry.info("ldap-hr-import",
                    "LDAP HR ingestion completed: " + imported.size() + " rows loaded into hr"));
            ldapJob.complete(imported.isEmpty() ? Status.PARTIAL : Status.SUCCESS);
        } catch (Exception e) {
            log.error("LDAP HR pre-ingestion failed: {}", e.getMessage(), e);
            ldapJob.addLog(LogEntry.error("ldap-hr-import",
                    "LDAP HR ingestion failed: " + e.getMessage(), e));
            ldapJob.complete(Status.FAILED);
        }
        return ldapJob;
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
