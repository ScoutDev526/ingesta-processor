# Architecture

This document describes the internal architecture of the ingesta-processor: how the code is laid out, how data flows through it, and which design decisions shape the behavior you observe at runtime.

## 1. Hexagonal layout

The codebase is split into two top-level packages under `es.ing.icenterprise.arthur`:

- **`core/`** — pure domain. Knows nothing about Spring beyond stereotype annotations, nothing about HTTP, JDBC, LDAP, POI, or YAML. Composed of:
  - `core/domain/` — entities, value objects, enums, definitions, factory, exceptions
  - `core/ports/inbound/` — driving contracts (use cases) implemented by services
  - `core/ports/outbound/` — driven contracts (gateways) implemented by adapters
  - `core/services/` — implementations of the inbound ports; orchestrate domain objects through outbound ports
  - `core/utils/` — pure helpers ([ColumnNormalizer](../src/main/java/es/ing/icenterprise/arthur/core/utils/ColumnNormalizer.java))
  - `core/config/` — minimal Spring configuration

- **`adapters/`** — everything that talks to the outside world. Two sub-packages:
  - `adapters/inbound/` — REST controllers, scheduler
  - `adapters/outbound/` — YAML loader, file downloaders, file readers, JDBC, LDAP, notifications, report exporters, archive, cleanup

The cardinal rule: **`core` must not import from `adapters`.** Adapters depend on core ports; core depends on no adapter. The lone exception is [IngestaService](../src/main/java/es/ing/icenterprise/arthur/core/services/IngestaService.java) which holds a reference to `ExcelReportStore` (an in-memory bean that lives under `adapters/outbound/report`) — a pragmatic shortcut to avoid promoting that store to a port.

## 2. Execution flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      Inbound adapters                                     │
│   ManualTriggerController    LdapQueryController     SchedulerAdapter     │
│        (POST /execute)        (GET /ldap/hr)         (@Scheduled cron)    │
└─────────────┬──────────────────────┬──────────────────────┬───────────────┘
              │                      │                      │
              ▼                      ▼                      ▼
   ┌───────────────────────┐ ┌────────────────────┐ ┌───────────────────────┐
   │ ExecuteProcessUseCase │ │ ExtractDataHr      │ │ ExecuteProcessUseCase │
   │ (ExecuteCommand)      │ │ UseCase            │ │ (ExecuteCommand       │
   │                       │ │                    │ │  .fromScheduler())    │
   └───────────┬───────────┘ └─────────┬──────────┘ └───────────┬───────────┘
               │                       │                        │
               ▼                       ▼                        │
   ┌──────────────────────────────────────────────────┐         │
   │                  Core services                    │◀────────┘
   │                                                   │
   │  IngestaService                                   │
   │   │                                               │
   │   ├─▶ ExtractDataHrService ──▶ LdapRepository    │
   │   │   (LDAP → HR table)         (Spring LDAP)    │
   │   │                                               │
   │   ├─▶ YamlScannerPort                            │
   │   ├─▶ JobDefinitionLoaderPort                    │
   │   ├─▶ FileDownloaderPort (RESOURCES | SHAREPOINT)│
   │   ├─▶ JobFactory (definition → Job/Task/Step)    │
   │   │                                               │
   │   ├─▶ DefaultJobProcessor                        │
   │   │     ├─ FileReaderPort (EXCEL | XML)          │
   │   │     ├─ ColumnAutoMapper                      │
   │   │     │    └─ TableMetadataPort                │
   │   │     └─ PersistencePort                       │
   │   │                                               │
   │   ├─▶ DefaultMetricsCollector → ProcessReport    │
   │   ├─▶ ExecutionLogExporterPort → XLSX            │
   │   │      └─ ExcelReportStore (in-memory)         │
   │   ├─▶ NotificationPort (log/email/…)             │
   │   └─▶ ArchiveProcessedFilePort | CleanupPort     │
   │                                                   │
   │  RoleOwnershipService     ─▶ RoleQueryPort       │
   │  DepartmentUpdateService  ─▶ DepartmentQueryPort │
   └──────────────────────────────────────────────────┘
```

The end-to-end execution of `ExecuteProcessUseCase.execute(ExecuteCommand)` is implemented in [IngestaService.execute](../src/main/java/es/ing/icenterprise/arthur/core/services/IngestaService.java) and proceeds as follows:

1. **LDAP HR pre-ingestion.** [runLdapHrImport](../src/main/java/es/ing/icenterprise/arthur/core/services/IngestaService.java) calls `extractDataHrUseCase.extractDataHr()`. The LDAP-derived HR snapshot is what `DepartmentUpdateService` and `RoleOwnershipService` later query, so it must be refreshed first. A failure here is recorded as a `FAILED` synthetic job and does not abort the pipeline.
2. **YAML scan.** `YamlScannerPort.scanJobDefinitions()` lists every `.yml` / `.yaml` under the configured jobs directory.
3. **Definition load + filter.** Each YAML is parsed via `JobDefinitionLoaderPort` (SnakeYAML), then filtered by `enabled` and the optional `ExecuteCommand.jobFilter`.
4. **Per-definition: download → factory.** A `FileDownloaderPort` that supports the source type pulls the file into the working directory; `JobFactory.createJob` turns the definition into a runtime `Job` with its `Task`s and `Step`s. If the file can't be read, the job is added in `SKIPPED` state so it still appears in the report.
5. **Job processing.** With `ingesta.parallel-jobs > 1`, jobs run on a fixed-size `ExecutorService`; otherwise they run sequentially. Per-job execution is in `DefaultJobProcessor` (section 5).
6. **Metrics collection.** `MetricsCollector.collect(jobs, manuallyTriggered)` aggregates each job's metrics and logs into a single `ProcessReport`.
7. **Excel log generation.** `ExecutionLogExporterPort.export(jobs, reportTitle)` produces a multi-sheet XLSX; the bytes are saved both to `ExcelReportStore` (keyed by report id, served by `GET /report/{id}/excel`) and to the working directory as `ingesta-report-YYYY-MM-DD.xlsx`.
8. **Notification.** `NotificationPort.notify(report, NotificationType.EMAIL)` — currently implemented only by `LogNotificationAdapter`, which dumps a summary at INFO level.
9. **Post-process files.** For each job's data file: `.xlsx` files go through `ArchiveProcessedFilePort.archive` (moved to the cached directory with the `YYYY-MM-DD-` and optional `Full Dump ` prefixes stripped); all other files go through `CleanupWorkingDirectoryPort.delete`.

## 3. Domain model

### Execution hierarchy

```
Job  1───*  Task  1───*  Step
 │           │           │
 ├ Metrics   ├ Metrics   ├ Metrics
 └ LogEntry* └ LogEntry* └ LogEntry*
```

- **[Job](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/Job.java)** — top-level execution unit. Carries `id`, `name`, `filePath`, `fileType`, `batchSize`, `sheetIndex`, `processAllSheets`, list of `Task`s, `Metrics`, `LogEntry`s, `Status`. Lifecycle: `start()` → `RUNNING`, `complete(Status)` finalizes, `addLog(LogEntry)` increments error/warning counters automatically.
- **[Task](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/Task.java)** — a `TRANSFORMATION` or `PERSISTENCE` block of steps, with its own order, `stopOnFailure` flag, status, and metrics.
- **[Step](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/Step.java)** — atomic operation: one of the eleven `StepType` values. Holds a `parameters: Map<String, Object>` populated by `JobFactory` after merging job/task/step parameters.

### Data flow types

- **[Action](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/Action.java)** — one row of data; thin wrapper over `Map<String, Object>` (`column → value`).
- **[FullDumpResult](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/FullDumpResult.java)** — `headers: List<String>` + `data: List<Action>`.
- **[DatabaseMapping](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/DatabaseMapping.java)** — record with five fields (`excelColumn`, `dbColumn`, `autoGenerate`, `concatenate`, `separator`). Helpers: `isAutoGenerated()`, `isConcatenated()`, `isNormalField()`.

### Reporting

- **[Metrics](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/Metrics.java)** — `startTime`, `endTime`, `durationMs`, `recordsProcessed`, `recordsFailed`, `recordsSkipped`, `errorCount`, `warningCount`, `customMetrics: Map<String, Object>`.
- **[LogEntry](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/LogEntry.java)** — `timestamp`, `LogLevel` (TRACE / INFO / SUMMARY / WARN / ERROR), `source`, `message`, `Throwable`, `context: Map<String, String>`. Factories: `info()`, `warn()`, `error()`, `summary()`, `trace()`.
- **[ProcessReport](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/ProcessReport.java)** — full report aggregated from jobs. Built with a builder. Holds id, time bounds, total duration, `manuallyTriggered`, overall `Status`, list of `JobSummary`, all `ERROR` and `WARN` logs, and `AggregatedMetrics`.
- **[AggregatedMetrics](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/AggregatedMetrics.java)** — totals across jobs. Factory `fromJobs(List<Job>)` computes everything.
- **[JobSummary](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/JobSummary.java)**, **[TaskSummary](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/TaskSummary.java)**, **[SubtaskSummary](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/SubtaskSummary.java)** — flat read-only views for serialization.

### HR / LDAP

- **[PersonLdap](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/PersonLdap.java)** — raw LDAP entry: `samAccountName`, `manager` (DN), `department`, `mail`, `givenName`, `lastName`, `title`.
- **[ImportDataHrPerson](../src/main/java/es/ing/icenterprise/arthur/core/domain/model/ImportDataHrPerson.java)** — flattened HR row written to the `HR` table.

### Enums

| Enum | Values |
|---|---|
| [`Status`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/Status.java) | `PENDING`, `RUNNING`, `SUCCESS`, `PARTIAL`, `FAILED`, `SKIPPED` |
| [`FileType`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/FileType.java) | `EXCEL`, `XML` |
| [`TaskType`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/TaskType.java) | `TRANSFORMATION`, `PERSISTENCE` |
| [`StepType`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/StepType.java) | `TRIM`, `UPPERCASE`, `CONCATENATE`, `DEDUPLICATE`, `FILTER_NULL`, `LOOKUP`, `LINK_PARENT`, `SELECT`, `INSERT`, `TRUNCATE`, `VALIDATE_REFERENCE` |
| [`LogLevel`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/LogLevel.java) | `TRACE`, `INFO`, `SUMMARY`, `WARN`, `ERROR` |
| [`NotificationType`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/NotificationType.java) | `EMAIL` |
| [`ExportFormat`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/ExportFormat.java) | `PDF`, `EXCEL`, `CSV`, `HTML` |

## 4. Definitions and factory

YAML is parsed into immutable records under `core/domain/definition/ingest/`:

- **[JobDefinition](../src/main/java/es/ing/icenterprise/arthur/core/domain/definition/ingest/JobDefinition.java)** — compact constructor defaults `batchSize` to `500` if `<= 0`, `sheetIndex` to `0` if negative, and replaces `null` `parameters` / `tasks` with empty collections.
- **[TaskDefinition](../src/main/java/es/ing/icenterprise/arthur/core/domain/definition/ingest/TaskDefinition.java)** — `name`, `order`, `type` (TaskType string), `stopOnFailure`, `subtasks: List<StepDefinition>`, `parameters`.
- **[StepDefinition](../src/main/java/es/ing/icenterprise/arthur/core/domain/definition/ingest/StepDefinition.java)** — `name`, `order`, `type`, `parameters`.
- **[FileSourceDefinition](../src/main/java/es/ing/icenterprise/arthur/core/domain/definition/ingest/FileSourceDefinition.java)** — `type` (`RESOURCES` | `SHAREPOINT`), `location`, `locationAfterProcessing`.
- **[FileSourceLocationDefinition](../src/main/java/es/ing/icenterprise/arthur/core/domain/definition/ingest/FileSourceLocationDefinition.java)** — `path`, `properties`.

[**JobFactory**](../src/main/java/es/ing/icenterprise/arthur/core/domain/factory/ingest/JobFactory.java) bridges definition → runtime entities and enforces the **parameter-merge precedence**:

```java
mergedParams = new HashMap<>(jobParams);   // start with job-level params
mergedParams.putAll(taskParams);           // task overrides job
mergedParams.putAll(stepDef.parameters()); // step overrides task
```

It also injects `_batchSize` into the job-level params so `INSERT` steps can chunk by it. `canLoadFile(Path)` returns whether the downloaded data file exists and is readable; if not, the job is added to the run in `SKIPPED` state.

## 5. Step execution

[`DefaultJobProcessor`](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java) is the single switch where every `StepType` is handled. The decision to keep all step logic in one orchestrator (rather than one strategy class per step) is deliberate — see [CONTRIBUTING.md](CONTRIBUTING.md#why-one-switch).

For each job:

1. Pick a `FileReaderPort` with `supports(fileType)`.
2. If `processAllSheets`, call `getSheetNames(path)` and iterate; otherwise read the single `sheetIndex`.
3. Extract `ingestDate` from the filename via the regex `(\d{4}-\d{2}-\d{2})` ([source](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java#L23-L23)), falling back to today.
4. For each task: `TRANSFORMATION` → in-memory mutation of the `List<Action>`; `PERSISTENCE` → JDBC operations.
5. If any task throws, the job ends `PARTIAL` unless every task failed (`FAILED`). Tasks with `stopOnFailure: true` short-circuit the loop.

Step-by-step semantics live in [JOB-DEFINITIONS.md](JOB-DEFINITIONS.md).

## 6. Inbound ports

| Port | Method | Implemented by |
|---|---|---|
| [`ExecuteProcessUseCase`](../src/main/java/es/ing/icenterprise/arthur/core/ports/inbound/ExecuteProcessUseCase.java) | `ProcessReport execute(ExecuteCommand)` | `IngestaService` |
| [`ExtractDataHrUseCase`](../src/main/java/es/ing/icenterprise/arthur/core/ports/inbound/ExtractDataHrUseCase.java) | `List<PersonLdap> extractDataHr()` | `ExtractDataHrService` |
| [`ImportDataHrUseCase`](../src/main/java/es/ing/icenterprise/arthur/core/ports/inbound/ImportDataHrUseCase.java) | `void importDataHr(List<PersonLdap>)` | `ImportDataHrService` |

[`ExecuteCommand`](../src/main/java/es/ing/icenterprise/arthur/core/ports/inbound/ExecuteCommand.java) is a value object: `manuallyTriggered: boolean`, `jobFilter: List<String>`. Factories: `fromScheduler()`, `fromManual()`, `fromManual(List<String>)`. `shouldRunAll()` returns true when the filter is null or empty.

## 7. Outbound ports → adapters

| Port | Contract (summary) | Adapter(s) |
|---|---|---|
| [`YamlScannerPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/YamlScannerPort.java) | `List<Path> scanJobDefinitions()` | [`LocalYamlScannerAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/yaml/LocalYamlScannerAdapter.java) |
| [`JobDefinitionLoaderPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/JobDefinitionLoaderPort.java) | `JobDefinition load(Path)` | [`SnakeYamlJobDefinitionAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/yaml/SnakeYamlJobDefinitionAdapter.java) |
| [`FileDownloaderPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/FileDownloaderPort.java) | `Path download(FileSourceDefinition)`, `boolean supports(FileSourceType)` | [`LocalFileSystemDownloaderAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/download/LocalFileSystemDownloaderAdapter.java), [`SharepointDownloaderAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/download/SharepointDownloaderAdapter.java) (stub) |
| [`FileReaderPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/FileReaderPort.java) | `Stream<Map<String,Object>> read(Path[, params])`, `List<String> getSheetNames(Path)`, `FileMetadata readFileMetadata(Path)`, `boolean supports(FileType)` | [`ExcelFileReaderAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/reader/ExcelFileReaderAdapter.java), [`XmlFileReaderAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/reader/XmlFileReaderAdapter.java) |
| [`PersistencePort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/PersistencePort.java) | See [PERSISTENCE.md](PERSISTENCE.md) | [`JdbcPersistenceAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/JdbcPersistenceAdapter.java) |
| [`TableMetadataPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/TableMetadataPort.java) | `List<String> getColumnNames(table, schema)` | [`JdbcTableMetadataAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/JdbcTableMetadataAdapter.java) |
| [`NotificationPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/NotificationPort.java) | `void notify(ProcessReport, NotificationType)` | [`LogNotificationAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/notification/LogNotificationAdapter.java) |
| [`ReportExporterPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/ReportExporterPort.java) | `byte[] export(ProcessReport, ExportFormat)` | [`CsvReportExporterAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/report/CsvReportExporterAdapter.java) (CSV only) |
| [`ExecutionLogExporterPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/ExecutionLogExporterPort.java) | `byte[] export(List<Job>, String reportTitle)` | [`ExcelExecutionLogAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/report/ExcelExecutionLogAdapter.java) |
| [`ProcessReportRepository`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/ProcessReportRepository.java) | `save`, `findById` | [`InMemoryProcessReportRepository`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/InMemoryProcessReportRepository.java) |
| [`CleanupWorkingDirectoryPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/CleanupWorkingDirectoryPort.java) | `void delete(Path)` | [`CleanupWorkingDirectoryAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/cleanup/CleanupWorkingDirectoryAdapter.java) |
| [`ArchiveProcessedFilePort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/ArchiveProcessedFilePort.java) | `void archive(Path)` | [`ArchiveProcessedFileAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/archive/ArchiveProcessedFileAdapter.java) |
| [`LdapRepository`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/LdapRepository.java) | `search`, `searchOne` (with `SearchCriteria` + `AttributesMapper<T>`) | [`LdapQueryAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/ldap/LdapQueryAdapter.java) |
| [`DepartmentQueryPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/DepartmentQueryPort.java) | Domain lead lookup, manager-tree walk, insert dept | [`JdbcDepartmentQueryAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/JdbcDepartmentQueryAdapter.java) |
| [`RoleQueryPort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/RoleQueryPort.java) | Role-name → id map, parameterized query, truncate + batch insert `OWNER_ROLE` | [`JdbcRoleQueryAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/JdbcRoleQueryAdapter.java) |

[`InsertResult`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/InsertResult.java) is the value object returned by `insertData`: `inserted: int`, `failed: int`, with a `plus(InsertResult)` combinator and an `EMPTY` constant. [`SearchCriteria`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/SearchCriteria.java) carries the LDAP `base` DN and `filter`.

## 8. Services

| Service | Inbound port | Outbound ports it touches |
|---|---|---|
| [`IngestaService`](../src/main/java/es/ing/icenterprise/arthur/core/services/IngestaService.java) | `ExecuteProcessUseCase` | `YamlScannerPort`, `JobDefinitionLoaderPort`, `List<FileDownloaderPort>`, `JobFactory`, `JobProcessor`, `MetricsCollector`, `NotificationPort`, `CleanupWorkingDirectoryPort`, `ArchiveProcessedFilePort`, `ExecutionLogExporterPort`, `ExcelReportStore`, `ExtractDataHrUseCase` |
| [`DefaultJobProcessor`](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java) | `JobProcessor` (internal interface) | `List<FileReaderPort>`, `PersistencePort`, `ColumnAutoMapper` |
| [`DefaultMetricsCollector`](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultMetricsCollector.java) | `MetricsCollector` | none |
| [`ColumnAutoMapper`](../src/main/java/es/ing/icenterprise/arthur/core/services/ColumnAutoMapper.java) | — | `TableMetadataPort` |
| [`ExtractDataHrService`](../src/main/java/es/ing/icenterprise/arthur/core/services/ExtractDataHrService.java) | `ExtractDataHrUseCase` | `LdapRepository`, `ImportDataHrUseCase` |
| [`ImportDataHrService`](../src/main/java/es/ing/icenterprise/arthur/core/services/ImportDataHrService.java) | `ImportDataHrUseCase` | `ImportDataHrMapper`, `ImportDataHrDeleteMapper` (MyBatis) |
| [`DepartmentUpdateService`](../src/main/java/es/ing/icenterprise/arthur/core/services/DepartmentUpdateService.java) | — (called from `ManualTriggerController`) | `DepartmentQueryPort` |
| [`RoleOwnershipService`](../src/main/java/es/ing/icenterprise/arthur/core/services/RoleOwnershipService.java) | — (called from `ManualTriggerController`) | `RoleQueryPort` |

`JobProcessor` and `MetricsCollector` are internal collaboration interfaces (not "ports" in the strict sense; they live in `core/services/` and have no adapter side).

## 9. Design notes worth knowing

### Parameter merge order

Step parameters are computed once at `JobFactory.createStep`: `job → task → step`, with later layers overriding earlier ones. The processor also adds `_ingestDate` and (in multi-sheet mode) overrides `tableName` with the sheet name at runtime.

### Bisecting batch insert

`JdbcPersistenceAdapter.batchInsertBisect` wraps `jdbcTemplate.batchUpdate` inside a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`. On `DataAccessException`, the chunk is halved and each half is retried; recursion bottoms out at a single bad row, which is logged and counted as failed in the returned `InsertResult`. Good rows survive — see [PERSISTENCE.md](PERSISTENCE.md#bisecting-insert).

### Excel streaming

`ExcelFileReaderAdapter` opens the workbook with `XSSFReader` (POI 5.3) and pulls rows through a StAX `XMLStreamReader`. The full DOM is never materialized, so memory stays bounded regardless of file size. `XMLInputFactory` is hardened against XXE (`SUPPORT_DTD=false`, `isSupportingExternalEntities=false`). The reader handles shared strings, inline strings, formulas (`str`), booleans, errors, and numeric cells (with date detection via `DateUtil.isADateFormat`).

### Date inference from filename

Two regexes do the work:

- `(\d{4}-\d{2}-\d{2})` — `DefaultJobProcessor.DATE_IN_FILENAME` ([line 23](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java)) — extracts the ingest date used to filter `WHERE timestampColumn = ?`.
- `^\d{4}-\d{2}-\d{2}-(?:Full Dump\s+)?` — `ArchiveProcessedFileAdapter.DATE_AND_DUMP_PREFIX` — strips the date and optional `Full Dump ` marker from the filename when archiving, so the cached copy overwrites the previous run instead of accumulating.

### Auto-mapping rules

`ColumnAutoMapper` works against UPPER_SNAKE_CASE normalized headers. `ColumnNormalizer.normalize` strips accents (NFD + diacritics removal), splits camelCase, replaces any non-alphanumeric run (including `-`, `.`, `_`, spaces) with a single `_`, collapses repeated underscores, trims leading/trailing `_`, and uppercases. Explicit mappings from YAML always win over auto-mapped ones.

### `LocalFileSystemDownloaderAdapter` file selection

When `ingesta.data-directory` is set, the adapter treats `source.location.path` as a **suffix** and picks the matching file with the most recent `YYYY-MM-DD-` prefix (falling back to lexicographic name on ties). A boundary check ensures `Processes-ES` does not match `Sub-Processes-ES`: a leading `-` is only accepted as a boundary if it closes a date prefix. Without `data-directory`, the adapter resolves the path as filesystem-first, classpath-fallback.

### What's not wired

- `SharepointDownloaderAdapter` is a stub and throws `UnsupportedOperationException`.
- `CsvReportExporterAdapter` is registered against `ReportExporterPort` but `IngestaService` does not call it — only the Excel execution log is generated end-to-end.
- `NotificationType.EMAIL` is the only notification type, and `LogNotificationAdapter` ignores it (always logs to SLF4J).

See [CONTRIBUTING.md](CONTRIBUTING.md) for adding new step types, file types, source types, or adapters.
