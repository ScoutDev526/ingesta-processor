# Operations

Day-2 guide for running the ingesta-processor: build, deployment, scheduling, file lifecycle, observability, and troubleshooting.

## 1. Build artifacts

```bash
mvn clean package
```

Produces `target/arthur-0.1.0-SNAPSHOT.jar` — an executable Spring Boot fat JAR. The Maven plugin is configured to exclude Lombok at packaging time (it is `optional` and only needed at compile time).

Run it standalone:

```bash
java -jar target/arthur-0.1.0-SNAPSHOT.jar
```

Container-friendly invocation:

```bash
java \
  -Dspring.config.location=file:/etc/ingesta/application.yml \
  -Dingesta.jobs.path=/etc/ingesta/jobs \
  -jar /opt/ingesta/arthur.jar
```

There is **no Dockerfile in this repo**. Build one with a multi-stage `eclipse-temurin:21-jre` base, copy the fat JAR, and expose `8080`.

## 2. Running modes

### Manual / on-demand

The REST API is the canonical trigger. Operators call:

```bash
curl -X POST http://localhost:8080/api/ingesta/execute
```

…optionally with `?jobs=action-import,risk-import` to scope the run. The response carries the `id` of the resulting `ProcessReport`; the operator downloads the Excel log via `GET /api/ingesta/report/{id}/excel`.

See [API.md](API.md) for every endpoint.

### Scheduled

[`SchedulerAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/inbound/scheduler/SchedulerAdapter.java) runs `ExecuteCommand.fromScheduler()` on the cron expression configured by:

```yaml
ingesta:
  scheduler:
    enabled: true
    cron: "0 0 2 * * ?"   # daily at 02:00 server-local
```

The default is `enabled: false`; flip it explicitly per environment. The cron uses Spring's 6-field format (`sec min hour day month weekday`).

To run at 02:00 UTC regardless of the host clock, either start the JVM in UTC (`-Duser.timezone=UTC`) or use cron quartz syntax that pins the timezone — Spring's built-in expressions do not support per-expression timezones.

## 3. File lifecycle

```
┌──────────────────┐   download    ┌────────────────────┐
│ data-directory   │ ────────────▶ │ working-directory  │  ← processed here
│ (inbox / share)  │               │ /tmp/ingesta       │
└──────────────────┘               └─────────┬──────────┘
                                             │
                          archive (.xlsx) ──┼── cleanup (other)
                                             │
                                             ▼
                                   ┌────────────────────┐
                                   │ cached-directory   │
                                   │ /tmp/ingesta/cached│
                                   └────────────────────┘
```

Three directories matter (see [CONFIGURATION.md](CONFIGURATION.md#6-file-staging-directories)):

| Directory | Property | Purpose |
|---|---|---|
| `data-directory` | `ingesta.data-directory` | Source inbox. When set, the downloader scans it and picks the newest match. |
| `working-directory` | `ingesta.working-directory` | Staging area. The downloader copies the chosen file here; readers and persistence read from here; the generated Excel report (`ingesta-report-YYYY-MM-DD.xlsx`) is also written here. |
| `cached-directory` | `ingesta.cached-directory` | Archive. After a successful ingestion, `.xlsx` files are moved here with the `YYYY-MM-DD-` and optional `Full Dump ` prefixes stripped — so the latest run overwrites the previous snapshot. |

### File matching at the inbox

[`LocalFileSystemDownloaderAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/download/LocalFileSystemDownloaderAdapter.java) treats `source.location.path` (in the YAML) as a **suffix** when `data-directory` is set. Example — given:

```
/mnt/inbox/2026-03-01-Full Dump Actions-ES.xlsx
/mnt/inbox/2026-03-06-Full Dump Actions-ES.xlsx
/mnt/inbox/2026-02-28-Full Dump Sub-Processes-ES.xlsx
```

…and the YAML `source.location.path: Actions-ES`, the adapter picks `2026-03-06-Full Dump Actions-ES.xlsx`. The boundary check ensures `Processes-ES` does NOT match the `Sub-Processes-ES` file (a bare `-` is not accepted as a boundary unless it closes a date prefix).

Date parsing failures fall back to lexicographic name comparison.

### Post-processing

For each job, after the job finishes (regardless of `SUCCESS` / `PARTIAL` / `FAILED`):

- if the file name ends with `.xlsx` (case-insensitive) → [`ArchiveProcessedFileAdapter.archive`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/archive/ArchiveProcessedFileAdapter.java) moves it to `cached-directory`, stripping the date and optional `Full Dump ` prefix.
- everything else → [`CleanupWorkingDirectoryAdapter.delete`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/cleanup/CleanupWorkingDirectoryAdapter.java) deletes it from `working-directory`.

Both adapters log warnings and continue on I/O failures; they never throw.

## 4. Reading the execution report

Every `/execute` produces an XLSX with:

- **Sheet 1** — global log titled by `ingesta.report.title` (default `ESClassificationSystem`).
- **Sheets 2..N** — one per normalized job name (language suffixes stripped, names truncated to Excel's 31 chars, duplicates suffixed `-2`, `-3`, …).
- **Columns** — `TIMESTAMP | SEVERITY | STEP | MESSAGE`. Frozen header, auto-filter.

Color coding from [`ExcelExecutionLogAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/report/ExcelExecutionLogAdapter.java):

| Severity | Row color | When |
|---|---|---|
| `TRACE` | light grey | Per-row debug detail (missing VALIDATE_REFERENCE values, LINK_PARENT misses) |
| `INFO` | white | Lifecycle messages |
| `SUMMARY` | light green | Per-step roll-up (`Concatenated …`, `LOOKUP … N resolved`) |
| `WARN` | light yellow | Missing optional parameters, unmapped columns, recoverable issues |
| `ERROR` | light red | Step/task/job failures |

Tab colors by job status: `SUCCESS` green · `PARTIAL` yellow · `SKIPPED` blue · `FAILED` red · other black.

The report is held in `ExcelReportStore` (in-memory `Map<UUID, byte[]>`) until the JVM restarts. A copy is also written to `${ingesta.working-directory}/ingesta-report-YYYY-MM-DD.xlsx` on every run — keep that for post-mortem after a restart.

## 5. Logs

- Default level: `INFO` for `es.ing.icenterprise.arthur`. Tune via `logging.level.<package>`.
- [`LogNotificationAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/notification/LogNotificationAdapter.java) writes a final summary block at the end of every run:

  ```
  === Ingesta Process Report ===
  ID: 8e9b3c1f-44ab-4a4a-bf9d-0a3a1e22fa12
  Status: SUCCESS
  Duration: 12345 ms
  Triggered: Manual
  Total Jobs: 25 | Success: 24 | Failed: 1
  Records Processed: 184320 | Failed: 7 | Success Rate: 99.9%
  ```

- Useful loggers to enable when investigating:

  | Package | Level | What it reveals |
  |---|---|---|
  | `es.ing.icenterprise.arthur` | `DEBUG` | Step dispatch, auto-mapping decisions, bisect retries |
  | `org.springframework.jdbc.core` | `DEBUG` | `JdbcTemplate` SQL + parameter values |
  | `org.springframework.ldap` | `DEBUG` | LDAP queries |
  | `org.apache.poi` | `DEBUG` | Excel parsing internals (verbose; only when investigating reader issues) |

## 6. Health checks

- `GET /api/ingesta/health` — process liveness.
- `GET /api/ingesta/test` — process + clock sanity check.
- `GET /api/ingesta/ldap/ok?base=DC=…` — LDAP reachability with a chosen base DN.

There is no built-in Spring Boot Actuator. Add `spring-boot-starter-actuator` if you need `/actuator/health`, metrics, etc.

## 7. Troubleshooting matrix

### "File not found in filesystem nor classpath: X"

`LocalFileSystemDownloaderAdapter` could not locate the file. Check:

- `ingesta.data-directory` matches where the file actually lives.
- The YAML `source.location.path` is the **suffix**, not the full filename.
- Filename does not start with a date your boundary check rejects (e.g. `26-03-06-Foo.xlsx` won't match — only the four-digit-year prefix is accepted).

Without `data-directory`, the path is resolved as a filesystem path first, then a classpath resource. Verify both.

### "Excel columns without DB match (ignored): [...]"

`ColumnAutoMapper` warning. The header normalized form does not exist in the target table. Either:

- rename the Excel column,
- rename the DB column, or
- add an explicit `mappings` entry in the YAML so the auto-mapper stops complaining.

If you intend to drop that column, the WARN is informational only — no failure.

### "DB columns without mapping (will be NULL or default): [...]"

The target table has columns that nobody filled. If the column is `NOT NULL` without a default, the `INSERT` will fail and the bisecting retry will isolate every row. Add an `autoGenerate: TIMESTAMP|UUID` mapping or an explicit `excelColumn → dbColumn` mapping.

### "Skipping bad row for X: values=[...] error=..."

`JdbcPersistenceAdapter.batchInsertBisect` isolated a single bad row. The log entry has the values and the root-cause SQL message. Common causes:

- string value truncated (`value too long for column X`),
- timestamp column expecting a `TIMESTAMP` but getting a free-form string,
- foreign key violation (the upstream `VALIDATE_REFERENCE` step did not catch it because the target table is not the reference),
- non-null constraint on an unmapped column.

The job continues with the rest of the batch. Counts surface in `InsertResult` and in the step's metrics.

### LDAP step fails

`runLdapHrImport` catches the exception and marks the synthetic `ldap-hr-import` job as `FAILED`; the rest of the pipeline still runs. Symptoms in downstream jobs:

- `LOOKUP` against `HR` returns mostly nulls,
- `RoleOwnershipService` / `DepartmentUpdateService` produce empty results.

Verify connectivity with `GET /api/ingesta/ldap/ok?base=...` and check that `LDAP_USER` / `LDAP_PASSWORD` are exported.

### Oracle vs H2 column case

If `ColumnAutoMapper.resolve` reports zero matches, the two-pass `JdbcTableMetadataAdapter` probe likely returned nothing. Confirm:

- the schema name (e.g. `INGESTA`) matches the DB user's effective schema,
- the table actually exists in that schema (Oracle's `USER_TABLES` view will show it).

`ddl-auto: update` will auto-create missing tables in dev; in production the schema is managed externally.

### Multi-sheet job: one sheet maps to nothing

`processAllSheets: true` overrides `tableName` per sheet with the sheet name. If a sheet name does not match a real table, `JdbcTableMetadataAdapter` returns an empty column list and every header ends up unmapped. Rename the sheet in the source workbook or split the file.

### "Failed to send notification: ..." in logs

Cosmetic — the pipeline does not depend on the notification. If the default `LogNotificationAdapter` is the only one wired, this should never happen. With a custom adapter (email, Slack), this means the channel failed; the run otherwise completed.

## 8. Capacity planning

- Memory: bounded by the largest in-memory `List<Action>` for a single sheet. Roughly `rowCount × averageRowSize × 1.5`. Excel reader itself is streaming; the bottleneck is what the transformation steps mutate. 4 GB heap handles ~1.5M rows of typical schema in tests; raise if you process bigger sheets.
- CPU: dominated by SQL throughput, not Java. The bisecting insert is `O(n)` in the happy path and `O(n + f log n)` with `f` bad rows.
- DB connections: `ingesta.parallel-jobs > 1` will multiply DB load. Each job in flight holds a `JdbcTemplate` connection during its persistence task; size your HikariCP pool accordingly.

## 9. Disabling pieces in production

| To disable | Setting |
|---|---|
| Scheduled runs | `ingesta.scheduler.enabled=false` |
| H2 console (recommended off in prod) | `spring.h2.console.enabled=false` |
| SQL logging | leave defaults; `spring.jpa.show-sql=false` |
| Cleanup / archive | not configurable — they always run. Mount `cached-directory` on a volume you can prune. |

## 10. Recovery from a failed run

A run that ends `PARTIAL` or `FAILED` does not need rollback — the `TRUNCATE WHERE timestampColumn = ?` step deletes only today's snapshot, so re-running the same date is idempotent for snapshot tables.

For relation tables (`*_ISSUES`, `ETL_LOG`, …): re-running will append, not replace. If you need a clean redo, manually `DELETE FROM <relation_table> WHERE TIMESTAMP = ?` before re-triggering, then `POST /api/ingesta/execute`.

`OWNER_ROLE` is wiped per-timestamp by `RoleOwnershipService` itself, so re-running `POST /api/ingesta/roles/ownership` is always safe. Same for `DEPARTMENT2` updates — they only append new rows.
