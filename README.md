# Ingesta Processor

A Spring Boot ETL processor built with **Hexagonal Architecture** (Ports & Adapters) for ingesting data from Excel and XML files into a database.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Inbound Adapters                         │
│  ┌──────────────────┐  ┌──────────────────────────────────┐ │
│  │ REST Controller   │  │ Scheduler (Cron)                 │ │
│  └────────┬─────────┘  └──────────┬───────────────────────┘ │
├───────────┼──────────────────────┼──────────────────────────┤
│           │   Core Inbound Ports │                           │
│  ┌────────▼──────────────────────▼───────────────────────┐  │
│  │           ExecuteProcessUseCase                        │  │
│  │              ExecuteCommand                            │  │
│  └────────────────────┬──────────────────────────────────┘  │
│                       │                                      │
│  ┌────────────────────▼──────────────────────────────────┐  │
│  │              Core Domain                               │  │
│  │  JobDefinition → JobFactory → Job → Task → Step       │  │
│  │  ProcessReport, Metrics, AggregatedMetrics             │  │
│  └────────────────────┬──────────────────────────────────┘  │
│                       │                                      │
│  ┌────────────────────▼──────────────────────────────────┐  │
│  │           Core Outbound Ports                          │  │
│  │  YamlScannerPort, FileDownloaderPort, FileReaderPort  │  │
│  │  PersistencePort, NotificationPort, ...               │  │
│  └────────────────────┬──────────────────────────────────┘  │
├───────────────────────┼─────────────────────────────────────┤
│                       │   Outbound Adapters                  │
│  ┌───────────┐ ┌──────▼──────┐ ┌────────────┐ ┌──────────┐│
│  │ YAML/     │ │ Download    │ │ Reader     │ │Persistence││
│  │ SnakeYaml │ │ Local/SP    │ │ Excel/XML  │ │ JDBC     ││
│  └───────────┘ └─────────────┘ └────────────┘ └──────────┘│
└─────────────────────────────────────────────────────────────┘
```

## Processing Flow

1. **Scan** YAML job definitions from configured directory
2. **Load** each definition via SnakeYaml
3. **Download** data files (local filesystem or SharePoint)
4. **Create** Job domain objects via `JobFactory`
5. **Process** each job: read file → transform data → persist to DB
6. **Collect** metrics and build `ProcessReport`
7. **Notify** via email (optional)
8. **Cleanup** working directory

## Tech Stack

- Java 21
- Spring Boot 3.3
- EasyExcel (Excel reading)
- Jackson XML (XML reading)
- SnakeYAML (job definition parsing)
- H2 (default database)
- JUnit 5 + Mockito + AssertJ (testing)
- JaCoCo (code coverage)

## Quick Start

```bash
# Build
mvn clean package

# Run
mvn spring-boot:run

# Run tests
mvn test

# Generate coverage report
mvn verify
# Report at target/site/jacoco/index.html
```

## API

### Trigger Manual Execution
```bash
# Run all jobs
curl -X POST http://localhost:8080/api/ingesta/execute

# Run specific jobs
curl -X POST "http://localhost:8080/api/ingesta/execute?jobs=employee-import,sales-import"
```

### Health Check
```bash
curl http://localhost:8080/api/ingesta/health
```

## Job Definition (YAML)

```yaml
name: employee-import
description: Import employee data from Excel
enabled: true
fileType: EXCEL
batchSize: 500
source:
  type: RESOURCES          # RESOURCES | SHAREPOINT
  location:
    path: /data/employees.xlsx
  locationAfterProcessing: /data/processed/

tasks:
  - name: clean-data
    order: 1
    type: TRANSFORMATION
    stopOnFailure: true
    subtasks:
      - name: trim-whitespace
        order: 1
        type: TRIM
      - name: uppercase-names
        order: 2
        type: UPPERCASE

  - name: persist-data
    order: 2
    type: PERSISTENCE
    stopOnFailure: true
    subtasks:
      - name: truncate-table
        order: 1
        type: TRUNCATE
      - name: insert-records
        order: 2
        type: INSERT
```

## Configuration

Key properties in `application.yml`:

| Property | Default | Description |
|---|---|---|
| `ingesta.jobs.path` | `classpath:jobs` | Directory with YAML job definitions |
| `ingesta.working-directory` | `/tmp/ingesta` | Temp directory for file processing |
| `ingesta.scheduler.enabled` | `false` | Enable cron-based execution |
| `ingesta.scheduler.cron` | `0 0 2 * * ?` | Cron expression (daily at 2am) |
| `ingesta.notification.enabled` | `false` | Enable email notifications |

## Project Structure

```
src/main/java/com/example/ingesta/
├── core/
│   ├── domain/
│   │   ├── model/          # Job, Task, Step, Metrics, ProcessReport, enums
│   │   ├── definition/     # JobDefinition, TaskDefinition, StepDefinition
│   │   └── factory/        # JobFactory
│   ├── port/
│   │   ├── inbound/        # ExecuteProcessUseCase, ExecuteCommand
│   │   └── outbound/       # All outbound port interfaces
│   └── service/            # IngestaService, JobProcessor, MetricsCollector
├── adapter/
│   ├── inbound/
│   │   ├── rest/           # ManualTriggerController
│   │   └── scheduler/      # SchedulerAdapter
│   └── outbound/
│       ├── yaml/           # LocalYamlScanner, SnakeYamlLoader
│       ├── download/       # Local, SharePoint downloaders
│       ├── reader/         # Excel, XML readers
│       ├── persistence/    # JDBC adapter
│       ├── notification/   # Email adapter
│       ├── report/         # CSV export adapter
│       └── cleanup/        # Working directory cleanup
└── config/                 # Spring configuration
```
