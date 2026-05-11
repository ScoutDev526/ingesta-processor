# Contributing

This document covers the common extension points: adding a new `StepType`, a new file type, a new data source, an outbound adapter, or a REST endpoint. It also captures the code-style guardrails the team relies on.

Before changing anything, read [ARCHITECTURE.md](ARCHITECTURE.md) — the hexagonal split is load-bearing and most "obvious" shortcuts violate it.

## 1. Adding a new `StepType`

The processor handles every step in a single `switch` inside [`DefaultJobProcessor`](../src/main/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessor.java). Steps belong to one of two `TaskType`s — `TRANSFORMATION` (in-memory mutation of `List<Action>`) or `PERSISTENCE` (DB calls via `PersistencePort`).

### Steps

1. **Add the enum entry.** Edit [`StepType`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/StepType.java). Place it next to its siblings (transformation vs persistence) and respect the existing comment grouping.

2. **Add the `case` in the switch.** Edit `DefaultJobProcessor.applyTransformation` (for `TRANSFORMATION`) or `DefaultJobProcessor.executePersistenceStep` (for `PERSISTENCE`). Pattern:

   ```java
   case MY_NEW_STEP -> {
       // 1. Pull required parameters with defaults
       String required = (String) step.getParameters().get("requiredParam");
       if (required == null) {
           step.addLog(LogEntry.warn(step.getName(),
                   "MY_NEW_STEP missing required parameter: 'requiredParam'"));
           break;
       }

       // 2. Do the work
       // ... mutate `data` or call persistencePort

       // 3. Emit a SUMMARY log so the Excel report has something useful
       step.addLog(LogEntry.summary(step.getName(), "MY_NEW_STEP: processed N rows"));
   }
   ```

3. **Add a unit test** in [`DefaultJobProcessorTest`](../src/test/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessorTest.java). Cover the happy path, missing-parameter case (must warn, not throw), and the metrics/log side effects. See [TESTING.md](TESTING.md#new-steptype).

4. **Document in [JOB-DEFINITIONS.md](JOB-DEFINITIONS.md)** with a parameter table and a YAML snippet.

5. **Add a real YAML example** to one of the production jobs under [src/main/resources/jobs/](../src/main/resources/jobs/) if the step is meant for general use.

### Why one switch <a id="why-one-switch"></a>

The natural OOP refactor is one strategy class per `StepType`, autowired by Spring and dispatched via a `Map<StepType, StepHandler>`. We deliberately avoided it because:

- Every step needs the same context: `Step`, `List<Action>`, `List<String> excelHeaders`, `LocalDate ingestDate`, `String sheetTableName`, the mapping resolver, the persistence port. Passing that context through a polymorphic interface is more ceremony than the switch.
- Reading the processor top-to-bottom shows the entire run in one place. With separate handlers, you'd jump between 11+ files to follow the same flow.
- Step types are not extension points exposed to third parties — they ship with the application. There is no "plug-in" use case that benefits from inversion of control.

If a step ever grows past ~80 lines of logic, extract it to a private method (e.g. `executeLinkParent`, `executeValidateReference` already do this) rather than promote it to a strategy class.

## 2. Adding a new file type

Currently `FileType` has `EXCEL` and `XML`. To add another:

1. **Add the enum entry** to [`FileType`](../src/main/java/es/ing/icenterprise/arthur/core/domain/enums/FileType.java).

2. **Implement `FileReaderPort`** as a new adapter under `src/main/java/es/ing/icenterprise/arthur/adapters/outbound/reader/`. Required methods:

   ```java
   @Component
   public class MyFormatFileReaderAdapter implements FileReaderPort {
       @Override
       public Stream<Map<String, Object>> read(Path filePath) { … }

       @Override
       public Stream<Map<String, Object>> read(Path filePath, Map<String, Object> params) { … }

       @Override
       public List<String> getSheetNames(Path filePath) { return List.of("default"); }

       @Override
       public FileMetadata readFileMetadata(Path filePath) { … }

       @Override
       public boolean supports(FileType type) { return type == FileType.MY_FORMAT; }
   }
   ```

3. **Stream, don't load.** Match the memory profile of [`ExcelFileReaderAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/reader/ExcelFileReaderAdapter.java) — return a lazy `Stream`. Holding the whole file in memory will fail on the bigger production files.

4. **Add a unit test** with at least one happy-path and one malformed-input case.

The processor will discover your reader through Spring's `List<FileReaderPort>` autowiring; you don't have to register it anywhere else.

## 3. Adding a new source type

`FileSourceType` currently has `RESOURCES` (implemented) and `SHAREPOINT` (stub).

1. **Add the enum entry** to [`FileSourceType`](../src/main/java/es/ing/icenterprise/arthur/core/domain/definition/ingest/FileSourceType.java).

2. **Implement `FileDownloaderPort`** under `src/main/java/es/ing/icenterprise/arthur/adapters/outbound/download/`. The contract is `Path download(FileSourceDefinition)` (return the local path of the staged file) and `boolean supports(FileSourceType)`.

3. **Copy the working-directory convention.** Always stage the file into `${ingesta.working-directory}` so the cleanup / archive flow can find it.

4. Use [`SharepointDownloaderAdapter`](../src/main/java/es/ing/icenterprise/arthur/adapters/outbound/download/SharepointDownloaderAdapter.java) as the template for "not yet implemented" stubs — fail loudly with `UnsupportedOperationException` rather than silently no-op.

5. **Add an end-to-end test** that mocks the remote source (`WireMock`, an embedded HTTP server, …) — do not hit the real service in CI.

## 4. Adding a new outbound adapter

For any new port (alternative notification channel, different report format, a metrics sink, …):

1. **Define the port interface** under `core/ports/outbound/` if it doesn't exist yet. Keep it minimal — one verb, primitive / domain types only, no Spring annotations.

2. **Implement the adapter** under `adapters/outbound/<area>/`. Register as `@Component`.

3. **Wire it into the orchestrating service** by adding a constructor dependency. Prefer adding to `List<NotificationPort>` (or similar) over single-instance fields when multiple implementations should be invoked.

4. **Update [ARCHITECTURE.md](ARCHITECTURE.md#7-outbound-ports--adapters)** to add the row in the port → adapter table.

## 5. Adding a new REST endpoint

1. **Place the controller** in `adapters/inbound/rest/`. Reuse the existing controllers when the new endpoint is conceptually adjacent.

2. **Depend on an inbound port**, not a service implementation. If no port fits, create one. Controllers must not reach into `core/services/` (with the exception of `RoleOwnershipService` and `DepartmentUpdateService` — these don't currently have inbound ports because the operations are infrequent and ops-only).

3. **Use Spring Boot defaults** for serialization — return Maps or simple records, not domain entities (which carry `Metrics`, `LogEntry`s, and other internal state that does not belong in the API contract).

4. **Update [API.md](API.md).** Every endpoint must be in the API doc with parameters, response shape, and a curl example.

## 6. Code style

- **Java 21.** Use records for value objects, `switch` expressions for exhaustive matches, `var` for obviously typed locals, pattern matching where it reads cleanly.
- **Lombok** is on the classpath. Use `@Slf4j`, `@RequiredArgsConstructor`, `@Getter`, `@Builder` where they remove noise. Don't use `@Data` on domain entities — it forces a public no-arg constructor and breaks immutability invariants.
- **Spring stereotypes only.** No XML configuration, no manual bean wiring outside `@Configuration` classes. Use constructor injection.
- **Package boundaries** are enforced by convention, not by ArchUnit (yet). The rule:
  - `core/` must not import from `adapters/`. The single existing exception is the `ExcelReportStore` reference in `IngestaService`; do not add more.
  - `core/services/` may depend on `core/ports/*` and `core/domain/*`.
  - `core/domain/` must not depend on anything outside `core/domain/` and Java standard library.
- **No comments on the obvious.** Comments are for hidden invariants, surprising trade-offs, or "do not change because the SQL dialect requires this". Don't restate what the code says.
- **SLF4J** for logging via `private static final Logger log = LoggerFactory.getLogger(Foo.class)` or `@Slf4j`. Levels:
  - `INFO` for major lifecycle events (`Reading Excel file: …`, `Processing job: …`)
  - `DEBUG` for per-row / per-batch detail
  - `WARN` for recoverable issues (bad row skipped, missing column)
  - `ERROR` only for situations a human needs to investigate
- **Logging messages** use `{}` placeholders, not concatenation.

## 7. Commits and PRs

Recent history follows Conventional Commits:

- `feat(<scope>): ...` for new behavior
- `fix(<scope>): ...` for bug fixes
- `refactor(<scope>): ...` when behavior is unchanged
- `perf(<scope>): ...` for performance work
- `test(<scope>): ...` for test-only changes
- `docs(<scope>): ...` for documentation-only changes
- `chore(<scope>): ...` for build / tooling

Scopes seen in the log: `persistence`, `cleanup`, `download`, `excel`, `ldap`. Pick one of these or invent a tight scope; avoid bare commits like `fix: stuff`.

Pull request expectations:

- Title in the same format as the commit.
- Description states the *why*. The *what* should be obvious from the diff.
- Tests added for new behavior or for regressions; coverage of changed lines should not drop.
- If you touch SQL, mention which dialects were verified.
- Re-read the diff before requesting review — leftover prints, commented code, and unused imports are easy to miss.

## 8. Things to avoid

- **Don't bypass `ColumnAutoMapper`** by writing column mapping logic in step handlers — keep all header→column translation in one place.
- **Don't shortcut the parameter-merge order.** If your step needs a value from a parent scope, read it from `step.getParameters()` (which already has the merged map). Don't reach into the `Task` or `Job`.
- **Don't catch `Exception` and swallow.** The processor expects steps to throw on hard failures so `PARTIAL` / `FAILED` status is correct.
- **Don't add a new MyBatis mapper** for a one-off query. Use `JdbcTemplate` (via a new port if needed). MyBatis is only justified for the HR pipeline because of the multi-dialect requirements.
- **Don't reach into `ExcelReportStore` from `core/`** beyond the existing call in `IngestaService`. Promote it to a port instead if you need additional access.
- **Don't put credentials in `application.yml`.** Use environment variables; the LDAP block already shows the pattern.

## 9. Quick reference

| Task | Files to touch |
|---|---|
| New `StepType` | `StepType.java`, `DefaultJobProcessor.java`, `DefaultJobProcessorTest.java`, `JOB-DEFINITIONS.md` |
| New `FileType` | `FileType.java`, new `*FileReaderAdapter`, its test |
| New `FileSourceType` | `FileSourceType.java`, new `*DownloaderAdapter`, its test |
| New outbound adapter | new port (if needed), new adapter, wire-in change, `ARCHITECTURE.md` update |
| New REST endpoint | controller in `adapters/inbound/rest/`, possibly new inbound port, `API.md` |
