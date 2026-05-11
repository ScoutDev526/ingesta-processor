# Ingesta Processor

A Spring Boot ETL processor built with **Hexagonal Architecture** that ingests Excel and XML files into a relational database, refreshes employee data from LDAP, and recomputes role / department ownership on demand. Jobs are declarative YAML; every step (trim, concatenate, dedup, lookup, validate-reference, link-parent, truncate, insert, …) is dispatched by a single processor that emits a colored multi-sheet execution log.

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      Inbound adapters                                     │
│   ManualTriggerController    LdapQueryController     SchedulerAdapter     │
│        (REST API)             (LDAP debug)            (@Scheduled cron)   │
└─────────────┬──────────────────────┬──────────────────────┬───────────────┘
              │                      │                      │
              ▼                      ▼                      ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │                              Core                                    │
   │   ExecuteProcessUseCase / ExtractDataHrUseCase / ImportDataHrUseCase │
   │                                                                      │
   │   IngestaService → JobFactory → DefaultJobProcessor                  │
   │                      │              │                                │
   │                      │              ├─ ColumnAutoMapper              │
   │                      │              └─ PersistencePort               │
   │   DepartmentUpdateService   RoleOwnershipService                     │
   └─────────────┬──────────────────────────────────────────┬─────────────┘
                 │                                          │
                 ▼                                          ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │                          Outbound adapters                           │
   │  YAML (SnakeYAML) · Download (local FS / SharePoint stub) ·          │
   │  Reader (POI XSSFReader+StAX / Jackson XML) ·                        │
   │  Persistence (Spring JDBC + MyBatis) · LDAP (Spring LDAP) ·          │
   │  Notification (log) · Report (XLSX execution log, CSV) ·             │
   │  Archive · Cleanup                                                   │
   └─────────────────────────────────────────────────────────────────────┘
```

For the deep version of the diagram, the domain model, and the design notes (bisecting insert, Excel streaming, parameter-merge order), see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tech stack

- **Java 21** · `var`, records, switch expressions, pattern matching
- **Spring Boot 3.3.5** · Web · Data JPA (Hibernate bootstraps schema only — JDBC and MyBatis do the work) · Data LDAP · Validation
- **Apache POI 5.3** with `XSSFReader` + StAX streaming · **Jackson XML** · **SnakeYAML 2.2**
- **Spring JDBC** + **MyBatis 3.0.3** (for the HR import mappers)
- **H2** in-memory by default (drop-in Oracle / PostgreSQL)
- **JUnit 5** + **Mockito** + **AssertJ** · **JaCoCo 0.8.12**
- **Lombok**

## Quick start

```bash
# Build
mvn clean package

# Run with the default H2 in-memory DB and the bundled job catalog
mvn spring-boot:run

# Run all tests + generate the JaCoCo report
mvn verify
#   → open target/site/jacoco/index.html

# Trigger a manual ingestion
curl -X POST http://localhost:8080/api/ingesta/execute
```

## REST endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/ingesta/execute` (`?jobs=a,b,c`) | Run the pipeline. Returns a `ProcessReport` summary with an `id`. |
| `POST` | `/api/ingesta/roles/ownership` (`?date=YYYY-MM-DD`) | Recompute the `OWNER_ROLE` table from the day's ingested data. |
| `POST` | `/api/ingesta/departments/update` (`?date=YYYY-MM-DD`) | Classify and insert new departments into `DEPARTMENT2`. |
| `GET` | `/api/ingesta/report/{id}/excel` | Download the colored multi-sheet execution log. |
| `GET` | `/api/ingesta/health` | Liveness probe. |
| `GET` | `/api/ingesta/test` | App + clock sanity check. |
| `GET` | `/api/ingesta/ldap/hr` | Force an LDAP read and refresh the `HR` table. |
| `GET` | `/api/ingesta/ldap/ok?base=...` | LDAP connectivity check. |

Examples and response shapes in [docs/API.md](docs/API.md).

## YAML job (excerpt)

Full reference in [docs/JOB-DEFINITIONS.md](docs/JOB-DEFINITIONS.md); 25 production jobs live under [src/main/resources/jobs/](src/main/resources/jobs/).

```yaml
name: action-import
enabled: true
fileType: EXCEL
batchSize: 500

source:
  type: RESOURCES
  location:
    path: Action-XX        # suffix; resolves to "2026-03-06-Full Dump Action-ES.xlsx"

parameters:
  schema: INGESTA

tasks:
  - name: clean-data
    order: 1
    type: TRANSFORMATION
    stopOnFailure: true
    subtasks:
      - { name: trim,    order: 1, type: TRIM }
      - name: build-id
        order: 2
        type: CONCATENATE
        parameters:
          sourceColumns: ["Folder_Path", "Identifier"]
          targetColumn: "ID"
          separator: "/"

  - name: persist-data
    order: 2
    type: PERSISTENCE
    stopOnFailure: true
    subtasks:
      - { name: truncate, order: 1, type: TRUNCATE,
          parameters: { tableName: ACTION, timestampColumn: TIMESTAMP } }
      - { name: validate-owner, order: 2, type: VALIDATE_REFERENCE,
          parameters: { fieldColumn: Action_Owner, referenceTable: HR,
                        referenceIdColumn: ID, timestampColumn: TIMESTAMP,
                        etlLogTable: ETL_LOG, currentEntityType: Action } }
      - name: insert
        order: 3
        type: INSERT
        parameters:
          tableName: ACTION
          autoMap: true
          mappings:
            - { dbColumn: "ID", concatenate: ["Folder_Path", "Identifier"], separator: "/" }
            - { dbColumn: "TIMESTAMP", autoGenerate: "TIMESTAMP" }
            - { excelColumn: "Action_Name", dbColumn: "NAME" }
```

## Configuration cheat-sheet

| Property | Default | Description |
|---|---|---|
| `ingesta.jobs.path` | `classpath:jobs` | Directory of YAML job files (or `file:/etc/ingesta/jobs`). |
| `ingesta.data-directory` | (empty) | When set, the downloader scans this dir and picks the newest match by `YYYY-MM-DD-` prefix. |
| `ingesta.working-directory` | `/tmp/ingesta` | Where files land before processing; also where the daily Excel report is written. |
| `ingesta.cached-directory` | `/tmp/ingesta/cached` | Archive destination for processed `.xlsx` files (date prefix stripped). |
| `ingesta.parallel-jobs` | `1` | Concurrent jobs. Shares one DB pool — tune both together. |
| `ingesta.scheduler.enabled` | `false` | Enable cron firing. |
| `ingesta.scheduler.cron` | `0 0 2 * * ?` | Spring cron, server-local timezone. |
| `spring.ldap.urls` | `${LDAP_URL:ldap://ad.ing.net:389}` | Corporate AD endpoint. |

Full reference in [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## Project layout

```
src/main/java/es/ing/icenterprise/arthur/
├── core/
│   ├── domain/
│   │   ├── model/          Job, Task, Step, Metrics, ProcessReport, …
│   │   ├── definition/     JobDefinition, TaskDefinition, StepDefinition
│   │   ├── enums/          Status, FileType, TaskType, StepType, LogLevel, …
│   │   └── factory/        JobFactory (parameter-merge: job → task → step)
│   ├── ports/
│   │   ├── inbound/        ExecuteProcessUseCase, ExtractDataHrUseCase, ImportDataHrUseCase
│   │   └── outbound/       YamlScannerPort, FileDownloaderPort, FileReaderPort,
│   │                       PersistencePort, TableMetadataPort, LdapRepository,
│   │                       ArchiveProcessedFilePort, CleanupWorkingDirectoryPort, …
│   ├── services/           IngestaService, DefaultJobProcessor, DefaultMetricsCollector,
│   │                       ColumnAutoMapper, ExtractDataHrService, ImportDataHrService,
│   │                       DepartmentUpdateService, RoleOwnershipService
│   └── utils/              ColumnNormalizer (UPPER_SNAKE_CASE)
└── adapters/
    ├── inbound/
    │   ├── rest/           ManualTriggerController, LdapQueryController
    │   └── scheduler/      SchedulerAdapter
    └── outbound/
        ├── yaml/           LocalYamlScannerAdapter, SnakeYamlJobDefinitionAdapter
        ├── download/       LocalFileSystemDownloaderAdapter, SharepointDownloaderAdapter (stub)
        ├── reader/         ExcelFileReaderAdapter (XSSFReader+StAX), XmlFileReaderAdapter
        ├── persistence/    JdbcPersistenceAdapter (bisecting insert), JdbcTableMetadataAdapter,
        │                   JdbcDepartmentQueryAdapter, JdbcRoleQueryAdapter,
        │                   ImportDataHr*Mapper, InMemoryProcessReportRepository
        ├── ldap/           LdapQueryAdapter, PersonLdapAttributesMapper
        ├── notification/   LogNotificationAdapter
        ├── report/         ExcelExecutionLogAdapter, CsvReportExporterAdapter, ExcelReportStore
        ├── archive/        ArchiveProcessedFileAdapter
        └── cleanup/        CleanupWorkingDirectoryAdapter
```

## Documentation map

All docs live under `docs/`:

- **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** — domain model, ports/adapters, execution flow, design notes
- **[API.md](docs/API.md)** — REST endpoints with request/response shapes and curl examples
- **[CONFIGURATION.md](docs/CONFIGURATION.md)** — every `application.yml` knob and what reads it
- **[JOB-DEFINITIONS.md](docs/JOB-DEFINITIONS.md)** — YAML reference, every `StepType` with its parameters
- **[PERSISTENCE.md](docs/PERSISTENCE.md)** — JDBC behavior, bisecting insert, HR/Role/Department pipelines
- **[TESTING.md](docs/TESTING.md)** — test layout, conventions, how to add tests
- **[CONTRIBUTING.md](docs/CONTRIBUTING.md)** — adding step types, file types, source types, adapters, REST endpoints
- **[OPERATIONS.md](docs/OPERATIONS.md)** — deployment, scheduling, file lifecycle, troubleshooting
