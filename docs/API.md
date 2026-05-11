# REST API

All endpoints live under `/api/ingesta`. Two controllers implement them:

- [`ManualTriggerController`](../src/main/java/es/ing/icenterprise/arthur/adapters/inbound/rest/ManualTriggerController.java) — `/api/ingesta/**`
- [`LdapQueryController`](../src/main/java/es/ing/icenterprise/arthur/adapters/inbound/rest/LdapQueryController.java) — `/api/ingesta/ldap/**`

The default port is `8080`. There is no authentication layer; rely on network controls or add Spring Security if you expose the service publicly.

## 1. Ingestion

### `POST /api/ingesta/execute`

Runs the full ETL pipeline once: LDAP HR pre-ingest → YAML scan → download → process → metrics → notify → archive/cleanup.

**Query parameters**

| Name | Type | Required | Description |
|---|---|---|---|
| `jobs` | comma-separated list | no | Run only the named jobs (matched against `JobDefinition.name`). When omitted, every enabled job runs. |

**Request body** — none.

**Response 200** — JSON summary of the resulting `ProcessReport`:

```json
{
  "id": "8e9b3c1f-44ab-4a4a-bf9d-0a3a1e22fa12",
  "status": "SUCCESS",
  "durationMs": 12345,
  "totals": {
    "totalJobs": 25,
    "successfulJobs": 24,
    "failedJobs": 1,
    "recordsProcessed": 184320,
    "successRate": 96.0
  }
}
```

`status` is one of `SUCCESS | PARTIAL | FAILED | SKIPPED`. The `id` is the handle you pass to `GET /report/{id}/excel` to download the multi-sheet log.

**Errors** — currently always returns `200`; individual job failures show up in `failedJobs` and in the downloadable Excel report.

**Examples**

```bash
# Run everything
curl -X POST http://localhost:8080/api/ingesta/execute

# Run a subset
curl -X POST "http://localhost:8080/api/ingesta/execute?jobs=action-import,risk-import"
```

### `POST /api/ingesta/roles/ownership`

Runs [`RoleOwnershipService.execute(timestamp)`](../src/main/java/es/ing/icenterprise/arthur/core/services/RoleOwnershipService.java) — recomputes the `OWNER_ROLE` table from the HR + Risk + Control + Issue + Action + Process + Asset + Event + CoE tables that the main pipeline has already loaded for that day. Each role has a parameterized SQL in the static `ROLE_QUERIES` map; matched owner CKs are truncated and batch-inserted.

**Query parameters**

| Name | Type | Required | Description |
|---|---|---|---|
| `date` | `YYYY-MM-DD` | no | Snapshot date. Defaults to today. |

**Response 200**

```json
{
  "status": "SUCCESS",
  "timestamp": "2026-05-11",
  "rolesProcessed": 21,
  "totalRecords": 8432
}
```

**Example**

```bash
curl -X POST "http://localhost:8080/api/ingesta/roles/ownership?date=2026-05-11"
```

### `POST /api/ingesta/departments/update`

Runs [`DepartmentUpdateService.execute(timestamp)`](../src/main/java/es/ing/icenterprise/arthur/core/services/DepartmentUpdateService.java) — finds employees in `HR` whose department is missing from `DEPARTMENT2`, walks the manager tree to the first domain lead, classifies the department as `IT` (when the domain is `CIO`) or `Business`, and inserts it.

**Query parameters**

| Name | Type | Required | Description |
|---|---|---|---|
| `date` | `YYYY-MM-DD` | no | HR snapshot date. Defaults to today. |

**Response 200**

```json
{
  "status": "SUCCESS",
  "timestamp": "2026-05-11",
  "departmentsProcessed": 312,
  "departmentsInserted": 4
}
```

**Example**

```bash
curl -X POST "http://localhost:8080/api/ingesta/departments/update"
```

## 2. Reports

### `GET /api/ingesta/report/{reportId}/excel`

Downloads the Excel execution log generated during a previous `POST /execute`.

**Path parameters**

| Name | Type | Description |
|---|---|---|
| `reportId` | UUID | The `id` returned by `/execute`. |

**Responses**

| Code | Body | When |
|---|---|---|
| `200` | XLSX bytes, `Content-Disposition: attachment; filename="ingesta-report-<reportId>.xlsx"` | Report found. |
| `400` | empty | `reportId` is not a valid UUID. |
| `404` | empty | No report with that id (typically because the application restarted — the store is in memory). |

The report is stored in [`ExcelReportStore`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/report/ExcelReportStore.java), a process-local `Map<UUID, byte[]>`. A copy is also written to `${ingesta.working-directory}/ingesta-report-YYYY-MM-DD.xlsx` on every run as a side-channel for ops.

Workbook layout (from [`ExcelExecutionLogAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/report/ExcelExecutionLogAdapter.java)):

- **Sheet 1** — global log titled after `ingesta.report.title` (default `ESClassificationSystem`).
- **Sheets 2..N** — one per normalized job name (language suffixes `-es`/`-en`/`-fr`/`-de`/`-it`/`-pt` are stripped, names truncated to Excel's 31-char limit, duplicates suffixed with `-2`, `-3`, …).
- Columns: `TIMESTAMP | SEVERITY | STEP | MESSAGE`. Header is frozen, auto-filter on.
- Row colors: TRACE grey · INFO white · SUMMARY green · WARN yellow · ERROR red.
- Tab colors: SUCCESS green · PARTIAL yellow · SKIPPED blue · FAILED red.

**Example**

```bash
# 1) trigger a run, capture the id
ID=$(curl -s -X POST http://localhost:8080/api/ingesta/execute | jq -r .id)

# 2) download the log
curl -OJ "http://localhost:8080/api/ingesta/report/$ID/excel"
```

## 3. Health

### `GET /api/ingesta/health`

Liveness probe.

```bash
curl http://localhost:8080/api/ingesta/health
# {"status":"UP"}
```

### `GET /api/ingesta/test`

Application metadata + server-side timestamp. Useful for smoke-checking the deploy.

```bash
curl http://localhost:8080/api/ingesta/test
# {"application":"ingesta-processor","status":"UP","timestamp":"2026-05-11T08:00:00Z"}
```

## 4. LDAP

### `GET /api/ingesta/ldap/hr`

Forces an LDAP read using the same hard-coded `SearchCriteria` set as the main pipeline ([`ExtractDataHrService`](../src/main/java/es/ing/icenterprise/arthur/core/services/ExtractDataHrService.java)) and returns the result as JSON. Useful for debugging LDAP availability and verifying the imported snapshot without running the full pipeline.

**Response 200**

```json
[
  {
    "samAccountName": "CK12345",
    "manager": "CN=Alice Manager,OU=People,DC=ad,DC=ing,DC=net",
    "department": "Risk - Domain CIO",
    "mail": "alice@example.com",
    "givenName": "Alice",
    "lastName": "Doe",
    "title": "Tribe Lead"
  }
]
```

**Note** — calling this endpoint also writes the snapshot to the `HR` table via `ImportDataHrService`. It is the same side effect as the LDAP step inside `/execute`.

### `GET /api/ingesta/ldap/ok?base={base}`

Connectivity check. Runs `ldapTemplate.search(base, "(objectClass=*)", SUBTREE_SCOPE, noop)` and returns `OK` if it succeeds, `FAIL` if it throws.

**Query parameters**

| Name | Type | Required | Description |
|---|---|---|---|
| `base` | string | yes | Base DN to search against. |

**Example**

```bash
curl "http://localhost:8080/api/ingesta/ldap/ok?base=DC=ad,DC=ing,DC=net"
# OK
```

## 5. Conventions

- All endpoints return JSON (`application/json`) except `/report/{id}/excel` (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`) and `/ldap/ok` (`text/plain`).
- `POST` endpoints have no request body — inputs are query parameters.
- No authentication is wired in. Put the service behind a reverse proxy or add Spring Security if it leaves the trusted network.
- The H2 console (`/h2-console`) is enabled by default; disable it in production via `spring.h2.console.enabled=false`.
