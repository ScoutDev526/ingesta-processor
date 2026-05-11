# Authoring YAML jobs

This is the manual for adding or editing a job. Every YAML file in [src/main/resources/jobs/](../src/main/resources/jobs/) describes one [`JobDefinition`](../src/main/java/es/ing/icenterprise/arthur/core/domain/definition/ingest/JobDefinition.java) — a complete ETL recipe for a single data file.

The parser is SnakeYAML 2.2; field names are case-sensitive and match the record components.

## 1. Top-level structure

```yaml
name: <unique-id>                # String, required
description: <human-readable>    # String, optional
enabled: true                    # Boolean, required (false → SKIPPED)
fileType: EXCEL                  # Enum (EXCEL | XML), required
batchSize: 500                   # Integer, defaults to 500 if missing or <= 0
sheetIndex: 0                    # Integer, defaults to 0 (Excel only)
processAllSheets: false          # Boolean, optional (Excel only)

source:
  type: RESOURCES                # Enum (RESOURCES | SHAREPOINT)
  location:
    path: <suffix-or-path>
  locationAfterProcessing: ...   # Optional, currently informational

parameters:
  # Job-wide parameters. Inherited by every task and step
  # unless overridden at a deeper level.
  schema: INGESTA
  # …

tasks:
  - name: clean-data
    order: 1
    type: TRANSFORMATION
    stopOnFailure: true
    parameters: {}               # Task-level overrides, optional
    subtasks:
      - name: trim-whitespace
        order: 1
        type: TRIM
        parameters: {}           # Step-level overrides, optional
```

**Defaults applied by [`JobDefinition`](../src/main/java/es/ing/icenterprise/arthur/core/domain/definition/ingest/JobDefinition.java) at load time:**

- `batchSize` `<= 0` → `500`
- `sheetIndex` `< 0` → `0`
- `parameters: null` → `{}`
- `tasks: null` → `[]`

## 2. Source

### `type: RESOURCES`

Local file resolution. The behavior depends on `ingesta.data-directory`:

| `data-directory` | What `path` means | Resolution |
|---|---|---|
| empty | absolute or relative filesystem path, or classpath resource | tries filesystem first, classpath second |
| set | **suffix** of the real filename | scans the directory, picks files whose name (minus extension) ends with the suffix at a real word boundary (whitespace, `_`, `.`, or a `-` that closes a `YYYY-MM-DD-` date prefix). Among matches, the file with the most recent `YYYY-MM-DD-` prefix wins; ties resolved by lexicographic name |

The boundary check in [`LocalFileSystemDownloaderAdapter.endsWithAtWordBoundary`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/download/LocalFileSystemDownloaderAdapter.java) deliberately rejects a bare `-` as a boundary, so `Processes-ES` does not match `Sub-Processes-ES`.

Examples:

```yaml
# data-directory empty, file lives in src/main/resources/data/
source:
  type: RESOURCES
  location:
    path: data/employees.xlsx

# data-directory=/mnt/inbox; picks the newest "*Actions-ES.xlsx"
source:
  type: RESOURCES
  location:
    path: Actions-ES
```

### `type: SHAREPOINT`

Reserved. [`SharepointDownloaderAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/download/SharepointDownloaderAdapter.java) is a stub that throws `UnsupportedOperationException`. Do not enable in production until it is implemented.

### `locationAfterProcessing`

Captured by [`FileSourceDefinition`](../src/main/java/es/ing/icenterprise/arthur/core/domain/definition/ingest/FileSourceDefinition.java) but **not** read by the current archive flow — [`ArchiveProcessedFileAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/archive/ArchiveProcessedFileAdapter.java) moves files into `ingesta.cached-directory` instead. Leave the field in for forward compatibility.

## 3. Parameters and the merge order

Parameters defined at `job`, `task`, and `step` level are merged into the runtime `Step.parameters` map by [`JobFactory.createStep`](../src/main/java/es/ing/icenterprise/arthur/core/domain/factory/ingest/JobFactory.java) in this exact order:

```text
job.parameters  →  task.parameters  →  step.parameters
                                       ^^^^ wins
```

`JobFactory` also injects `_batchSize` into the job-level params (sourced from `JobDefinition.batchSize`), and [`DefaultJobProcessor`](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java) adds `_ingestDate` per step at runtime. When `processAllSheets: true`, `tableName` is also overridden per sheet with the sheet name.

Common parameter keys (recognized by the processor):

| Key | Used by | Purpose |
|---|---|---|
| `tableName` | `TRUNCATE`, `INSERT`, `SELECT` | target table |
| `schema` | persistence ports | schema prefix |
| `timestampColumn` | `TRUNCATE`, `LOOKUP`, `VALIDATE_REFERENCE`, `LINK_PARENT` | DATE column used for snapshot filtering |
| `idColumn` | `INSERT` (skipExisting/upsert), `VALIDATE_REFERENCE`, `LINK_PARENT` | primary key column |
| `autoMap` | `INSERT` | toggle automatic header→column mapping (default `true`) |
| `mappings` | `INSERT` | explicit mappings (always win over auto-mapped) |
| `skipExisting` | `INSERT` | filter rows whose `idColumn` already exists |
| `upsertMode` | `INSERT` | update rows that already exist (implies `skipExisting` semantics) |
| `etlLogTable` | `VALIDATE_REFERENCE`, `LINK_PARENT` | log table for skipped/missing references |
| `currentEntityType` | `VALIDATE_REFERENCE`, `LINK_PARENT` | label used in `ETL_LOG` |

Keys prefixed with `_` are reserved for the framework — do not use them in YAML.

## 4. Tasks

A task is one of two types:

- **`TRANSFORMATION`** — mutates the in-memory `List<Action>` rows. Output is the same list, with values transformed and possibly filtered.
- **`PERSISTENCE`** — operates against the database via [`PersistencePort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/PersistencePort.java).

Tasks run in `order` (ascending). `stopOnFailure: true` aborts the rest of the job on the first task error; `false` continues and the job ends `PARTIAL` (or `FAILED` if every task failed).

## 5. Step reference

The full list lives in [`StepType`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/StepType.java). Each section below documents the parameters the processor reads, the behavior, and a minimal YAML snippet.

### `TRIM`

Trims leading/trailing whitespace on every `String` value in the row map.

```yaml
- name: trim-whitespace
  order: 1
  type: TRIM
```

No parameters.

### `UPPERCASE`

Uppercases every `String` value in the row map.

```yaml
- name: upper-everything
  order: 2
  type: UPPERCASE
```

No parameters.

### `CONCATENATE`

Joins values of multiple source columns into a single target column, per row.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `sourceColumns` | `List<String>` | yes | — | Excel headers to join (in order). |
| `targetColumn` | `String` | yes | — | Output column name added to the row map. |
| `separator` | `String` | no | `""` | Inserted between non-blank values. Blank values are skipped. |

```yaml
- name: build-id
  type: CONCATENATE
  parameters:
    sourceColumns: ["Folder_Path", "Identifier"]
    targetColumn: "ID"
    separator: "/"
```

### `DEDUPLICATE`

Removes rows with duplicate `keyColumn` values, keeping the first occurrence.

| Parameter | Type | Required |
|---|---|---|
| `keyColumn` | `String` | yes |

```yaml
- name: dedup-by-id
  type: DEDUPLICATE
  parameters:
    keyColumn: ID
```

### `FILTER_NULL`

Drops rows where the value at `column` is null or blank.

| Parameter | Type | Required |
|---|---|---|
| `column` | `String` | yes |

```yaml
- name: drop-empty-owner
  type: FILTER_NULL
  parameters:
    column: Risk_Owner
```

### `LOOKUP`

Resolves `sourceColumn` values against a reference table and writes the result to `targetColumn`. The lookup map is built once via `PersistencePort.lookupValues` — keys are lowercased, and if `timestampColumn` is provided the most recent snapshot wins.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `sourceColumn` | `String` | yes | — | column whose value is the key to resolve |
| `targetColumn` | `String` | yes | — | column where the resolved value is written |
| `referenceTable` | `String` | yes | — | table to look up against |
| `referenceKeyColumn` | `String` | yes | — | column in `referenceTable` matched against `sourceColumn` |
| `referenceValueColumn` | `String` | yes | — | column in `referenceTable` returned as result |
| `timestampColumn` | `String` | no | (no filter) | when set, only the latest snapshot is loaded |
| `nullValues` | `List<String>` | no | `[]` | source values that should resolve to `null` without hitting the map |

```yaml
- name: resolve-owner-id
  type: LOOKUP
  parameters:
    sourceColumn: Owner_Email
    targetColumn: Owner_Id
    referenceTable: HR
    referenceKeyColumn: mail
    referenceValueColumn: id
    timestampColumn: timestamp
    nullValues: ["N/A", "TBD"]
```

### `LINK_PARENT`

Conditional relationship loader: for each row, look at `parentTypeColumn`, pick the matching rule, verify the parent exists in `checkTable`, and insert a `(currentId, parentId, timestamp)` row into `relationTable`. Missing parents are written to `etlLogTable` as TRACE entries.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `idColumn` | `String` | yes | — | column with the current row's id |
| `parentPathColumn` | `String` | yes | — | path part of the parent id |
| `parentObjectsColumn` | `String` | yes | — | object part of the parent id |
| `parentTypeColumn` | `String` | yes | — | column with the parent type (used to pick a rule) |
| `separator` | `String` | no | `/` | joins path + objects to form the parent id |
| `checkIdColumn` | `String` | no | `ID` | id column inside `checkTable` |
| `id1Column` | `String` | no | `ID_1` | child id column in `relationTable` |
| `id2Column` | `String` | no | `ID_2` | parent id column in `relationTable` |
| `timestampColumn` | `String` | no | `TIMESTAMP` | timestamp column in both `checkTable` and `relationTable` |
| `schema` | `String` | no | — | schema for both tables |
| `etlLogTable` | `String` | no | — | when set, missing parents are logged to it |
| `currentEntityType` | `String` | no | `Unknown` | label written to `ETL_LOG` |
| `rules` | `List<Map>` | yes | — | one entry per `parentType` |

Each rule:

```yaml
rules:
  - parentType: SOXIssue          # matched against parentTypeColumn
    checkTable: ISSUES
    relationTable: ACTION_ISSUES
    parentEntityType: Issue       # optional, defaults to checkTable
    extraColumns:                 # optional, merged into the inserted relation row
      RELATIONSHIP_TYPE: PARENT
```

Full example from `action-import.yml`:

```yaml
- name: link-parent
  type: LINK_PARENT
  parameters:
    idColumn: ID
    parentPathColumn: Parent_Path
    parentObjectsColumn: Parent_Objects
    parentTypeColumn: Parent_Object_Types
    separator: "/"
    etlLogTable: ETL_LOG
    currentEntityType: Action
    rules:
      - parentType: SOXIssue
        checkTable: ISSUES
        relationTable: ACTION_ISSUES
        parentEntityType: Issue
```

### `VALIDATE_REFERENCE`

For each row, check that `fieldColumn`'s value exists in `referenceTable.referenceIdColumn` (filtered by `timestampColumn = ingestDate`). Missing values are cleared (set to empty string) and logged to `etlLogTable`. The set of valid ids is loaded once via `PersistencePort.loadReferenceIds`.

| Parameter | Type | Required | Default |
|---|---|---|---|
| `fieldColumn` | `String` | yes | — |
| `referenceTable` | `String` | yes | — |
| `referenceIdColumn` | `String` | no | `ID` |
| `timestampColumn` | `String` | no | `TIMESTAMP` |
| `idColumn` | `String` | no | — (used for log context only) |
| `schema` | `String` | no | — |
| `etlLogTable` | `String` | no | — |
| `currentEntityType` | `String` | no | `Unknown` |

```yaml
- name: validate-owner
  type: VALIDATE_REFERENCE
  parameters:
    fieldColumn: Action_Owner
    referenceTable: HR
    referenceIdColumn: ID
    timestampColumn: TIMESTAMP
    idColumn: ID
    etlLogTable: ETL_LOG
    currentEntityType: Action
```

### `SELECT`

Executes a raw `SELECT` to confirm the database is reachable / a known row exists. Result is discarded.

| Parameter | Type | Required | Default |
|---|---|---|---|
| `query` | `String` | no | `SELECT 1` |

```yaml
- name: ping-db
  type: SELECT
  parameters:
    query: "SELECT COUNT(*) FROM HR"
```

### `TRUNCATE`

Removes the existing snapshot before re-insert. Two modes:

- `timestampColumn` set → `DELETE FROM <tableName> WHERE <timestampColumn> = <ingestDate>`. This is the production mode — it removes only today's rows so multi-day history is preserved.
- `timestampColumn` absent → `TRUNCATE TABLE <tableName>`. Use only for non-snapshot tables.

| Parameter | Type | Required |
|---|---|---|
| `tableName` | `String` | yes (defaults to `ingesta_data` if omitted, which you almost never want) |
| `timestampColumn` | `String` | no — set it for snapshot tables |
| `schema` | `String` | no |

```yaml
- name: truncate-action
  type: TRUNCATE
  parameters:
    tableName: ACTION
    timestampColumn: TIMESTAMP
```

### `INSERT`

The workhorse step. Resolves column mappings (auto-map + explicit), then issues batched inserts. On batch failure, [bisects](PERSISTENCE.md#bisecting-insert) until the bad row is isolated.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `tableName` | `String` | yes (default `ingesta_data` if omitted) | — | target table |
| `schema` | `String` | no | — | schema prefix |
| `idColumn` | `String` | no | `ID` | used for `skipExisting` / `upsertMode` |
| `autoMap` | `Boolean` | no | `true` | when `true`, auto-resolves Excel headers via [`ColumnAutoMapper`](../src/main/java/es/ing/icenterprise/arthur/core/services/ColumnAutoMapper.java) |
| `mappings` | `List<Map>` | no | `[]` | explicit mappings; always win over auto-mapped |
| `skipExisting` | `Boolean` | no | `false` | filter out rows whose `idColumn` value is already in the table |
| `upsertMode` | `Boolean` | no | `false` | update existing rows in addition to inserting new ones |

#### Mapping types

Three flavors of `DatabaseMapping`:

1. **Normal** — straight Excel header to DB column.

   ```yaml
   - excelColumn: "Action_Name"
     dbColumn: "NAME"
   ```

2. **Auto-generated** — value generated by the system, no Excel column.

   ```yaml
   - dbColumn: "TIMESTAMP"
     autoGenerate: "TIMESTAMP"   # resolves to the ingest date at midnight
   - dbColumn: "PK"
     autoGenerate: "UUID"        # random UUIDv4
   ```

3. **Concatenated** — join multiple Excel columns into one DB column.

   ```yaml
   - dbColumn: "ID"
     concatenate: ["Folder_Path", "Identifier"]
     separator: "/"
   ```

`autoGenerate` values are case-insensitive: `TIMESTAMP` and `UUID` are the only ones implemented today; anything else logs a warning and inserts `null`.

#### Auto-mapping rules

`ColumnAutoMapper.resolve` (see [ARCHITECTURE.md](ARCHITECTURE.md#auto-mapping-rules)):

1. Read the target table's column names via `TableMetadataPort` (uppercase).
2. For each Excel header, normalize through [`ColumnNormalizer`](../src/main/java/es/ing/icenterprise/arthur/core/utils/ColumnNormalizer.java): accent strip → camelCase split → non-alphanumerics to `_` → collapse `_+` → uppercase.
3. If the normalized header matches a DB column **and** that DB column is not in an explicit mapping → auto-map it.
4. Explicit mappings are appended last and override on conflict.
5. Headers without a DB match are logged as WARN and ignored (the row's value for that header is dropped).
6. DB columns without any mapping are logged as WARN and will be inserted as `NULL` / default.

Naming consequences:

- Author DB columns in `UPPER_SNAKE_CASE`.
- Excel headers can be `"First Name"`, `"first_name"`, `"firstName"`, `"primer apellido"` — they all normalize to `FIRST_NAME` / `PRIMER_APELLIDO`.
- A `-` in the Excel header becomes a `_` (so `"Risk-Owner"` → `RISK_OWNER`).

#### Combining everything

```yaml
- name: insert-action
  type: INSERT
  parameters:
    tableName: ACTION
    schema: INGESTA
    autoMap: true
    mappings:
      - dbColumn: "ID"
        concatenate: ["Folder_Path", "Identifier"]
        separator: "/"
      - dbColumn: "TIMESTAMP"
        autoGenerate: "TIMESTAMP"
      - excelColumn: "Action_Name"
        dbColumn: "NAME"
      - excelColumn: "Action_Description"
        dbColumn: "DESCRIPTION"
```

#### Upsert / skipExisting

When `upsertMode: true` (or `skipExisting: true`):

- The processor calls `PersistencePort.loadExistingIds(tableName, schema, idColumn)` and partitions the in-memory rows into `newRows` (insert) and `existingRows` (update or skip).
- `upsertMode: true` calls `PersistencePort.updateData` for `existingRows` using `idColumn` in the WHERE clause; all mapped columns except the id are updated.
- `skipExisting: true` alone just drops `existingRows`.

The `idColumn` must be present in `mappings` (auto-mapped or explicit) — `JdbcPersistenceAdapter.updateData` throws otherwise.

## 6. Multi-sheet jobs

Set `processAllSheets: true` to run every task once per sheet. [`DefaultJobProcessor.processJobAllSheets`](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java) overrides `tableName` at runtime with the sheet name, so each sheet ends up in a same-named table. Useful for Excel files with one sheet per entity.

```yaml
name: multi-entity-import
fileType: EXCEL
processAllSheets: true
source:
  type: RESOURCES
  location:
    path: data/multi-entity.xlsx
tasks:
  - name: persist
    order: 1
    type: PERSISTENCE
    subtasks:
      - name: truncate
        type: TRUNCATE
        parameters:
          timestampColumn: TIMESTAMP
      - name: insert
        type: INSERT
        parameters:
          autoMap: true
```

Notes:

- Empty sheets are logged as WARN and skipped.
- Sheet names must match a real table; if the column metadata pass returns an empty list, every header ends up unmapped.

## 7. Walkthrough: `action-import.yml`

[`src/main/resources/jobs/action-import.yml`](../src/main/resources/jobs/action-import.yml) is a representative production job. Annotated:

```yaml
name: action-import
description: Import action data from Excel file (template - duplicate per country)
enabled: true
fileType: EXCEL
batchSize: 500

source:
  type: RESOURCES
  location:
    path: Action-XX                 # suffix; resolves to e.g. "2026-03-06-Full Dump Action-ES.xlsx"

parameters:
  schema: INGESTA                   # inherited by all persistence steps

tasks:
  - name: clean-data                # task 1: in-memory transformations
    order: 1
    type: TRANSFORMATION
    stopOnFailure: true
    subtasks:
      - name: trim-whitespace
        order: 1
        type: TRIM
      - name: build-id              # synthesize the ACTION.ID before persistence sees it
        order: 2
        type: CONCATENATE
        parameters:
          sourceColumns: ["Folder_Path", "Identifier"]
          targetColumn: "ID"
          separator: "/"

  - name: persist-data              # task 2: write to DB
    order: 2
    type: PERSISTENCE
    stopOnFailure: true
    subtasks:
      - name: truncate-action       # remove today's snapshot first
        order: 1
        type: TRUNCATE
        parameters:
          tableName: ACTION
          timestampColumn: TIMESTAMP

      - name: validate-action-owner # blank out unknown owners + log them
        order: 2
        type: VALIDATE_REFERENCE
        parameters:
          fieldColumn: Action_Owner
          referenceTable: HR
          referenceIdColumn: ID
          timestampColumn: TIMESTAMP
          idColumn: ID
          etlLogTable: ETL_LOG
          currentEntityType: Action

      - name: validate-delegate-action-owner
        order: 3
        type: VALIDATE_REFERENCE
        parameters:
          fieldColumn: Delegate_Action_Owner
          referenceTable: HR
          referenceIdColumn: ID
          timestampColumn: TIMESTAMP
          idColumn: ID
          etlLogTable: ETL_LOG
          currentEntityType: Action

      - name: insert-action         # auto-map the rest of the columns
        order: 4
        type: INSERT
        parameters:
          tableName: ACTION
          autoMap: true
          mappings:
            - dbColumn: "ID"        # synthesized from Folder_Path + Identifier (matches CONCATENATE above)
              concatenate: ["Folder_Path", "Identifier"]
              separator: "/"
            - dbColumn: "TIMESTAMP"
              autoGenerate: "TIMESTAMP"
            - excelColumn: "Action_Name"
              dbColumn: "NAME"
            - excelColumn: "Action_Description"
              dbColumn: "DESCRIPTION"

      - name: link-parent           # one ACTION can belong to one SOXIssue
        order: 5
        type: LINK_PARENT
        parameters:
          idColumn: ID
          parentPathColumn: Parent_Path
          parentObjectsColumn: Parent_Objects
          parentTypeColumn: Parent_Object_Types
          separator: "/"
          etlLogTable: ETL_LOG
          currentEntityType: Action
          rules:
            - parentType: SOXIssue
              checkTable: ISSUES
              relationTable: ACTION_ISSUES
              parentEntityType: Issue
```

## 8. Production job catalog

All 25 production jobs ship under [`src/main/resources/jobs/`](../src/main/resources/jobs/). Each follows the action-import shape: TRIM → CONCATENATE → TRUNCATE → VALIDATE_REFERENCE → INSERT → LINK_PARENT.

| File | Target table | What it imports |
|---|---|---|
| `action-import.yml` | `ACTION` | Remediation actions linked to issues |
| `auditreport-import.yml` | `AUDIT_REPORT` | Audit findings |
| `business-entity-import.yml` | `BUSINESS_ENTITY` | Org units / legal entities |
| `cmdb-import.yml` | `CMDB` | Configuration items |
| `control-import.yml` | `CONTROL` | Risk controls |
| `controlobjective-import.yml` | `CONTROL_OBJECTIVE` | Control objectives |
| `event-import.yml` | `EVENT` | Operational events |
| `hubwoo-contracts-import.yml` | `HUBWOO_CONTRACTS` | Third-party contracts (Hubwoo source) |
| `issue-import.yml` | `ISSUES` | Compliance / SOX issues |
| `itrmp-import.yml` | `ITRMP` | IT risk management plan |
| `minimum-standard-import.yml` | `MINIMUM_STANDARD` | Minimum control standards |
| `orx-import.yml` | `ORX` | ORX operational risk taxonomy |
| `policy-import.yml` | `POLICY` | Internal policies |
| `process-import.yml` | `PROCESS` | Business processes |
| `product-import.yml` | `PRODUCT` | Products |
| `regulation-import.yml` | `REGULATION` | External regulations |
| `risk-import.yml` | `RISK` | Risk register |
| `riskassessment-import.yml` | `RISK_ASSESSMENT` | Risk assessment results |
| `sample-employee-import.yml` | `employees` | Example / template — fully annotated in Spanish, kept for reference |
| `scopetesting-import.yml` | `SCOPE_TESTING` | In-scope test coverage |
| `subprocess-import.yml` | `SUBPROCESS` | Sub-processes |
| `testplan-import.yml` | `TEST_PLAN` | Test plans |
| `testresult-import.yml` | `TEST_RESULT` | Test outcomes |
| `thirdparties-import.yml` | `THIRD_PARTIES` | Third-party register |
| `valuechain-import.yml` | `VALUE_CHAIN` | Value-chain mapping |

When adding a new country variant, copy one of these and change `name`, `source.location.path`, and any country-specific column mapping.
