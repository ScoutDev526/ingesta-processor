# Persistence

This document describes the JDBC layer: how rows reach the database, how the bisecting insert isolates bad data, how the HR / Role / Department pipelines work, and which dialects are supported.

The single implementation of [`PersistencePort`](../src/main/java/es/ing/icenterprise/arthur/core/ports/outbound/PersistencePort.java) is [`JdbcPersistenceAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/JdbcPersistenceAdapter.java), built on Spring `JdbcTemplate`. Database schema is created via `spring.jpa.hibernate.ddl-auto=update`; there are no Liquibase / Flyway migrations in the repo.

## 1. `PersistencePort` methods

### `insertData(data, mappings, parameters) → InsertResult`

Issues `INSERT INTO <tableName> (<dbColumns>) VALUES (?, ?, …)` and batches via `JdbcTemplate.batchUpdate` inside a `TransactionTemplate` configured with `PROPAGATION_REQUIRES_NEW`. Each `?` is filled by `resolveValue` according to the mapping's flavor:

- **Normal** → `action.get(excelColumn)`
- **Auto-generated `TIMESTAMP`** → `Timestamp.valueOf(_ingestDate.atStartOfDay())`
- **Auto-generated `UUID`** → `UUID.randomUUID().toString()`
- **Concatenated** → join non-blank values of `concatenate` columns with `separator`
- **Unknown `autoGenerate`** → logged WARN, value is `null`

Parameters:

| Key | Required | Default | Notes |
|---|---|---|---|
| `tableName` | yes | `ingesta_data` | written into the SQL directly — must be a safe identifier |
| `_ingestDate` | injected | today | resolved from filename by `DefaultJobProcessor` |

`InsertResult` is `(inserted: int, failed: int)`. Callers in `DefaultJobProcessor.executePersistenceStep` accumulate it across batches and surface it via `Step.metrics.recordsProcessed` / `recordsFailed`.

### Bisecting insert

`batchInsertBisect` is the recursive heart of `insertData`:

```text
batchInsertBisect(rows):
  try:
    in REQUIRES_NEW tx → batchUpdate(rows)
    return (inserted = rows.size, failed = 0)
  catch DataAccessException:
    if rows.size == 1:
      log warn → return (0, 1)
    mid = rows.size / 2
    return bisect(left)  +  bisect(right)
```

Why it matters:

- A single bad row (constraint violation, value truncation, type mismatch) used to poison the whole batch. Now it is isolated to a single row, logged with its values and the root-cause message, and counted as `failed`. The other rows commit.
- `PROPAGATION_REQUIRES_NEW` is non-negotiable here — without it, the outer transaction would already be marked rollback-only by the time we retry the halved chunks.
- Recursion is `O(log n)` round-trips in the failure path; with no bad rows it's a single batch.

### `updateData(data, mappings, parameters, idColumn)`

Generates `UPDATE <tableName> SET <col> = ?, … WHERE <idColumn> = ?` and batches it. `idColumn` must be present in `mappings`; otherwise the call throws `IllegalArgumentException`. Used by `INSERT` step with `upsertMode: true`.

### `truncate(parameters)`

Two modes based on whether `timestampColumn` is present:

- `timestampColumn` set → `DELETE FROM <table> WHERE <timestampColumn> = ?` with `_ingestDate` as the parameter. This is the production mode — only today's snapshot is removed.
- `timestampColumn` absent → `TRUNCATE TABLE <table>`. Used for non-snapshot tables only.

### `check(data, parameters)`

Runs `parameters.query` (default `SELECT 1`) via `queryForObject(Object.class)`. Result is discarded; used by `SELECT` step as a readiness probe.

### `checkExists(tableName, schema, idColumn, idValue, timestampColumn, date) → boolean`

Single-row `SELECT COUNT(*) FROM <schema>.<table> WHERE <idColumn> = ? AND <timestampColumn> = ?`. Used by `LINK_PARENT` to decide whether a parent exists before inserting the relation.

### `insertRow(tableName, schema, columnValues)`

Single-row insert with the column list driven by the map keys. `LocalDate` values are converted to `Timestamp` at midnight. Used by `LINK_PARENT` for the relation row and by both `VALIDATE_REFERENCE` and `LINK_PARENT` for the `ETL_LOG` row.

### `loadReferenceIds(tableName, schema, idColumn, timestampColumn, date) → Set<Object>`

`SELECT <idColumn> FROM <schema>.<table> WHERE <timestampColumn> = ?` — collected into a `HashSet` for `VALIDATE_REFERENCE` to avoid N+1.

### `loadExistingIds(tableName, schema, idColumn) → Set<Object>`

`SELECT <idColumn> FROM <schema>.<table>` (no filter). Used by `INSERT` step with `skipExisting` / `upsertMode`.

### `lookupValues(tableName, schema, keyColumn, valueColumn, timestampColumn) → Map<String, String>`

Loads the whole reference table into memory once per `LOOKUP` step. SQL:

- With `timestampColumn`: `SELECT k, v FROM t WHERE timestampCol = (SELECT MAX(timestampCol) FROM t)`
- Without: `SELECT k, v FROM t`

Keys are lowercased so the `LOOKUP` step does case-insensitive matching. Use this for HR-id resolution, code tables, etc.

## 2. Column metadata

[`JdbcTableMetadataAdapter.getColumnNames`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/JdbcTableMetadataAdapter.java) probes `DatabaseMetaData.getColumns` in two passes:

1. First with `schema.toUpperCase()` + `tableName.toUpperCase()` (Oracle convention).
2. Falls back to the names as-is (PostgreSQL / H2 mixed case) if pass 1 returned nothing.

All column names are uppercased in the result. Always author DB columns in `UPPER_SNAKE_CASE` to match — this is also the form `ColumnNormalizer` produces from Excel headers, so the auto-mapper has a single canonical form.

## 3. HR pipeline (LDAP → `HR` table)

```
LdapTemplate ─▶ LdapQueryAdapter ─▶ ExtractDataHrService ─▶ ImportDataHrService ─▶ HR table
                  (PersonLdap)           (4 SearchCriteria)     (truncate + insert)
```

[`ExtractDataHrService`](../src/main/java/es/ing/icenterprise/arthur/core/services/ExtractDataHrService.java) runs four hard-coded `SearchCriteria` against the corporate AD (E3 / D2 / E2 / R2 OUs with specific filters), catches `LdapSearchException` per query (failure → empty list), concatenates the results, then synchronously calls `importDataHrService.importDataHr(...)`.

[`ImportDataHrService.importDataHr`](../src/main/java/es/ing/icenterprise/arthur/core/services/ImportDataHrService.java) is `@Transactional` and:

1. Sets `timestamp = today at midnight`.
2. Calls `ImportDataHrDeleteMapper.deleteByTimestamp(timestamp)` to wipe the day's rows.
3. Transforms `PersonLdap` → `ImportDataHrPerson`: extracts CK from `samAccountName`, strips `"mail:"` / `"sn:"` prefixes from LDAP attribute values, extracts the manager CN from the manager DN, builds `fullName = givenName + " " + lastName`.
4. Inserts every row through `ImportDataHrMapper.insertOne(...)`.

The MyBatis mappers ([`ImportDataHrMapper.java`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/ImportDataHrMapper.java) and [`ImportDataHrDeleteMapper.java`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/ImportDataHrDeleteMapper.java)) carry the SQL. The delete mapper was made portable across Oracle and H2 in commit `40a0523` — keep that compatibility in mind when changing the queries.

## 4. Department update flow

[`DepartmentUpdateService.execute(timestamp)`](../src/main/java/es/ing/icenterprise/arthur/core/services/DepartmentUpdateService.java) populates new entries in `DEPARTMENT2`:

1. Load domain-lead map: `Map<CK, roleName>` via `DepartmentQueryPort.getDomainLeadCkToRoleMap(timestamp)`.
2. Load "new departments": for each employee in `HR` whose `department` is missing from `DEPARTMENT2`, get `(ck, deptId)` pairs.
3. For each pair (deduplicated by `deptId`):
   - Walk the manager tree up via `getManagerTreeUp(ck, timestamp)`.
   - First manager that is a domain lead wins.
   - Extract `domain` as the prefix before the first `_` in the role name (e.g. `"CIO_DomainLead"` → `CIO`).
   - Classify type: `IT` when `domain == "CIO"`, else `Business`.
   - Insert `(deptId, deptType, domain)` into `DEPARTMENT2`.
4. Return `DepartmentUpdateResult(departmentsProcessed, departmentsInserted)`.

Triggered via `POST /api/ingesta/departments/update` ([API.md](API.md#post-apiingestadepartmentsupdate)).

## 5. Role ownership flow

[`RoleOwnershipService.execute(timestamp)`](../src/main/java/es/ing/icenterprise/arthur/core/services/RoleOwnershipService.java) recomputes `OWNER_ROLE` from already-ingested tables. The static `ROLE_QUERIES` map (`LinkedHashMap`) holds one entry per role:

```java
ROLE_QUERIES.put("Risk owner", new RoleQuery(
    "SELECT risk_owner, … FROM risk WHERE timestamp = ? AND … AND risk_owner IS NOT NULL …",
    1   // number of '?' parameters the timestamp fills
));
```

For each role:

1. `loadRoleNameToIdMapping()` — fetch `ROLES.name → ROLES.id`.
2. `executeRoleQuery(sql, timestamp, paramCount)` — bind `timestamp` once per `?`, collect the first column (owner CK) into a `Set`.
3. `truncateOwnerRoles(timestamp)` — wipe today's entries.
4. `batchInsertOwnerRoles(timestamp, roleId, ownerIds)` — bulk insert.

Roles currently configured (`HR` / `RISK` / `CONTROL` / `ISSUE` / `ACTION` / `PROCESS` / `ASSET` / `EVENT` / CoE tables):

CEO · Domain owner · TribeLead · Head/Lead · Risk owner · Risk owner delegate · Control owner · Control owner delegate · Issue owner · Issue owner delegate · Action owner · Action owner delegate · Process leader · Asset owner · Event owner · CoE IT Risk Officer · CoE Business Risk Officer · (and others — see the static block).

Triggered via `POST /api/ingesta/roles/ownership` ([API.md](API.md#post-apiingestarolesownership)).

## 6. Tables referenced

Based on the production YAMLs and the services, the schema in use includes (non-exhaustive):

| Table | Written by | Notes |
|---|---|---|
| `HR` | `ImportDataHrService` (LDAP path) | refreshed daily, timestamp at midnight |
| `ACTION`, `BUSINESS_ENTITY`, `CMDB`, `CONTROL`, `CONTROL_OBJECTIVE`, `EVENT`, `HUBWOO_CONTRACTS`, `ISSUES`, `ITRMP`, `MINIMUM_STANDARD`, `ORX`, `POLICY`, `PROCESS`, `PRODUCT`, `REGULATION`, `RISK`, `RISK_ASSESSMENT`, `SCOPE_TESTING`, `SUBPROCESS`, `TEST_PLAN`, `TEST_RESULT`, `THIRD_PARTIES`, `VALUE_CHAIN`, `AUDIT_REPORT` | their respective `*-import.yml` | snapshot pattern: `TIMESTAMP` column, `TRUNCATE WHERE TIMESTAMP = ?` then `INSERT` |
| `ACTION_ISSUES` (and similar relation tables) | `LINK_PARENT` step | `(ID_1, ID_2, TIMESTAMP)` |
| `ETL_LOG` | `VALIDATE_REFERENCE`, `LINK_PARENT` | columns `WHERE_A`, `A`, `WHERE_B`, `B`, `CAUSE`, `ACTION`, `TIMESTAMP` |
| `DEPARTMENT2` | `DepartmentUpdateService` | one row per new department, classified `IT` / `Business` |
| `OWNER_ROLE` | `RoleOwnershipService` | `(TIMESTAMP, ROLE_ID, OWNER_CK)` |
| `ROLES` | (read-only reference) | name → id mapping |

## 7. Transactions

- `JdbcPersistenceAdapter` opens a `PROPAGATION_REQUIRES_NEW` transaction per `batchInsertBisect` invocation. Adjacent calls (truncate, update, insertRow, …) inherit Spring's default `JdbcTemplate` behavior — no explicit transaction wraps them.
- `ImportDataHrService.importDataHr` is `@Transactional`; the truncate + N inserts commit together.
- There is no top-level transaction across an entire job or task. A `PARTIAL` job means earlier steps may have committed.

## 8. Dialect support

Tested informally against H2 (default) and Oracle. Notable gotchas:

- **Case sensitivity** — covered by the two-pass column-metadata probe; always uppercase column names in YAML.
- **`TRUNCATE TABLE`** — supported on both. Prefer the `DELETE WHERE timestampColumn = ?` mode anyway because it preserves history.
- **MyBatis HR delete mapper** — has dialect-aware SQL (commit `40a0523`); if you fork it, retest against both DBs.
- **`REGEXP_SUBSTR`** — used in `RoleOwnershipService.ROLE_QUERIES`. Oracle native; H2 supports it under compatibility mode but check before relying on these queries in tests against H2.
- **Date binding** — `LocalDate` values are converted to `java.sql.Timestamp` at midnight throughout the adapter for consistency.

## 9. Performance notes

- Insert throughput is dominated by `JdbcTemplate.batchUpdate`. `batchSize: 500` is the default and is usually a good fit; raise it for narrow tables, lower it if memory pressure or DB latency is high.
- `lookupValues` loads the full reference table into a `LinkedHashMap`. Watch the heap if you point it at a million-row table without a `timestampColumn` filter.
- `loadExistingIds` does the same and is called once per `INSERT` step with `skipExisting`/`upsertMode`. For big tables, consider whether the cost is worth the dedup.
- `parallel-jobs > 1` does not give a free speedup — all jobs share the same `JdbcTemplate` and the same DB connection pool. Bench before turning it on.
