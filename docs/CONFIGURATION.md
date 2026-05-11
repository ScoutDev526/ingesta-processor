# Configuration

Every runtime knob lives in [src/main/resources/application.yml](../src/main/resources/application.yml). This page documents each property: default, where it is read, and what to set it to.

You can override any value with an environment variable using Spring Boot's relaxed binding (`INGESTA_WORKING_DIRECTORY`, `SPRING_LDAP_URLS`, etc.) or with `-Dproperty=value` on the JVM command line.

## 1. Application

| Property | Default | Read by | Notes |
|---|---|---|---|
| `spring.application.name` | `ingesta-processor` | Spring Boot | Shown in logs and actuator endpoints. |

## 2. Database

| Property | Default | Read by | Notes |
|---|---|---|---|
| `spring.datasource.url` | `jdbc:h2:mem:ingestadb;DB_CLOSE_DELAY=-1` | Spring Boot | H2 in-memory DB for dev. Replace with an Oracle/PostgreSQL URL for production. |
| `spring.datasource.driver-class-name` | `org.h2.Driver` | Spring Boot | Set accordingly when changing DB. |
| `spring.datasource.username` | `sa` | Spring Boot | |
| `spring.datasource.password` | *(empty)* | Spring Boot | |
| `spring.h2.console.enabled` | `true` | Spring Boot | Disable in non-dev environments. |
| `spring.h2.console.path` | `/h2-console` | Spring Boot | |
| `spring.jpa.hibernate.ddl-auto` | `update` | Spring Boot | The project doesn't actually use JPA repositories — JDBC and MyBatis do the work — but Hibernate is on the classpath and bootstraps the schema. Keep `update` (or `none` in environments where the schema is managed externally). |
| `spring.jpa.show-sql` | `false` | Spring Boot | |
| `mybatis.configuration.map-underscore-to-camel-case` | `true` | MyBatis | Maps `SAM_ACCOUNT_NAME` → `samAccountName` for `ImportDataHr*Mapper`. |

### Oracle / PostgreSQL specifics

[`JdbcTableMetadataAdapter.getColumnNames`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/JdbcTableMetadataAdapter.java) probes the database in two passes:

1. First, with `schema.toUpperCase()` and `tableName.toUpperCase()` — matches the Oracle convention.
2. If that returns nothing, again with the names as passed — matches PostgreSQL / H2 mixed case.

Returned column names are always uppercased before being compared against normalized Excel headers in `ColumnAutoMapper`. Practical consequence: when authoring YAML, always write DB column names in **UPPER_SNAKE_CASE** to match what the metadata pass produces.

## 3. LDAP

| Property | Default | Read by | Notes |
|---|---|---|---|
| `spring.ldap.urls` | `${LDAP_URL:ldap://ad.ing.net:389}` | Spring LDAP | Resolved from the `LDAP_URL` env var if set, otherwise the default. |
| `spring.ldap.base` | `DC=ad,DC=ing,DC=net` | Spring LDAP | Base DN for searches. |
| `spring.ldap.username` | `${LDAP_USER:}` | Spring LDAP | Empty by default — anonymous bind unless overridden. |
| `spring.ldap.password` | `${LDAP_PASSWORD:}` | Spring LDAP | |

Use the `GET /api/ingesta/ldap/ok?base=...` endpoint to confirm connectivity before triggering an ingestion — see [API.md](API.md#ldap).

## 4. Scheduler

| Property | Default | Read by | Notes |
|---|---|---|---|
| `ingesta.scheduler.enabled` | `false` | [`SchedulerAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/inbound/scheduler/SchedulerAdapter.java) | Set to `true` to allow `@Scheduled` to actually run; otherwise the method returns early. |
| `ingesta.scheduler.cron` | `0 0 2 * * ?` | `SchedulerAdapter` | Spring cron expression (`sec min hour day month weekday`). The default fires daily at 02:00. |

The cron is evaluated using the server's timezone. To run at 02:00 UTC explicitly, either run the JVM in UTC (`-Duser.timezone=UTC`) or change the expression accordingly.

## 5. Job discovery

| Property | Default | Read by | Notes |
|---|---|---|---|
| `ingesta.jobs.path` | `classpath:jobs` | [`LocalYamlScannerAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/yaml/LocalYamlScannerAdapter.java) | Either `classpath:<resource-path>` to scan inside the JAR, or a filesystem path. The scanner walks the directory and picks up every `.yml` / `.yaml`. |

When packaging for production, mount the YAML jobs as an external directory and set `ingesta.jobs.path=/etc/ingesta/jobs` so jobs can be added without rebuilding the application.

## 6. File staging directories

| Property | Default | Read by | Notes |
|---|---|---|---|
| `ingesta.data-directory` | *(empty)* | [`LocalFileSystemDownloaderAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/download/LocalFileSystemDownloaderAdapter.java) | When non-empty, the adapter treats `source.location.path` in the YAML as a **suffix** and searches this directory for matching files. The most recent `YYYY-MM-DD-` prefix wins. Leave empty for classpath / direct-path resolution. |
| `ingesta.working-directory` | `/tmp/ingesta` | `LocalFileSystemDownloaderAdapter`, `IngestaService`, `CleanupWorkingDirectoryAdapter` | Where downloaded files land before processing. Also where the auto-generated `ingesta-report-YYYY-MM-DD.xlsx` is written. |
| `ingesta.cached-directory` | `/tmp/ingesta/cached` | [`ArchiveProcessedFileAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/archive/ArchiveProcessedFileAdapter.java) | Destination for `.xlsx` files after a successful ingestion. The `YYYY-MM-DD-` and optional `Full Dump ` prefixes are stripped from the filename so successive runs overwrite the previous snapshot. |

On Windows hosts you typically want something like:

```yaml
ingesta:
  data-directory: "C:/Ingesta/data"
  working-directory: "C:/Ingesta/work"
  cached-directory: "C:/Ingesta/cached"
```

## 7. Execution behavior

| Property | Default | Read by | Notes |
|---|---|---|---|
| `ingesta.parallel-jobs` | `1` | [`IngestaService`](../src/main/java/es/ing/icenterprise/arthur/core/services/IngestaService.java) | When `> 1`, jobs run on a fixed-size `ExecutorService`. With `1` they run sequentially. Note: `JdbcPersistenceAdapter` is process-wide and uses `PROPAGATION_REQUIRES_NEW`, so running jobs in parallel will serialize on the database connection pool — tune both together. |
| `ingesta.report.title` | `ESClassificationSystem` | `IngestaService` | Used as the global sheet name in the generated Excel execution log. |
| `ingesta.notification.enabled` | `true` | (reserved for future notification adapters) | The default `LogNotificationAdapter` ignores this flag and always logs the summary. The property is present so future adapters (email, Slack, …) can opt out cleanly. |

## 8. Logging

| Property | Default | Notes |
|---|---|---|
| `logging.level.es.ing.icenterprise.arthur` | `INFO` | Bump to `DEBUG` to see SQL generation, `XSSFReader` events, auto-mapper decisions, etc. |
| `logging.level.org.springframework.jdbc.core` | (Spring default) | `DEBUG` here prints `JdbcTemplate` parameters — handy when bisecting insert failures. |
| `logging.level.org.springframework.ldap` | (Spring default) | `DEBUG` for LDAP search debugging. |

## 9. Environment overrides at a glance

```bash
# Switch DB to Oracle
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@db.internal:1521/ARTHUR
SPRING_DATASOURCE_DRIVER_CLASS_NAME=oracle.jdbc.OracleDriver
SPRING_DATASOURCE_USERNAME=ingesta
SPRING_DATASOURCE_PASSWORD=••••

# Enable scheduling
INGESTA_SCHEDULER_ENABLED=true
INGESTA_SCHEDULER_CRON="0 0 3 * * MON-FRI"

# Point at an external jobs + data layout
INGESTA_JOBS_PATH=/etc/ingesta/jobs
INGESTA_DATA_DIRECTORY=/mnt/share/inbox
INGESTA_WORKING_DIRECTORY=/var/lib/ingesta/work
INGESTA_CACHED_DIRECTORY=/var/lib/ingesta/cached

# LDAP
LDAP_URL=ldaps://ad.corp.example:636
LDAP_USER="CN=ingesta-svc,OU=Service,DC=corp,DC=example"
LDAP_PASSWORD="••••"
```

## 10. Profile recommendations

| Concern | Dev | CI / test | Prod |
|---|---|---|---|
| DB | H2 in-memory | H2 in-memory + Testcontainers Oracle for `*IT` tests | External Oracle / Postgres |
| Scheduler | `enabled: false` | `enabled: false` | `enabled: true` |
| LDAP | optional (LDAP-dependent code degrades to `PARTIAL`) | mocked | corporate AD |
| `working-directory` | `/tmp/ingesta` or per-OS temp | per-test temp dir | dedicated mount with quota |
| Logging | `DEBUG` for the app package | `INFO` | `INFO`, `WARN` for noisy adapters |
