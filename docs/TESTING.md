# Testing

The repo ships ~33 test classes covering every layer. This page explains how they are organized, the conventions they follow, and how to add tests when you change the code.

## 1. Layout

The test tree mirrors `src/main/java` one-for-one, with two extra utility tests in `core/utils/`:

```
src/test/java/es/ing/icenterprise/arthur/
├── adapters/
│   ├── inbound/
│   │   ├── rest/
│   │   │   ├── LdapQueryControllerTest.java
│   │   │   ├── ManualTriggerControllerTest.java
│   │   │   └── PersonLdapMapperTest.java
│   │   └── scheduler/
│   │       └── SchedulerAdapterTest.java
│   └── outbound/
│       ├── archive/                ArchiveProcessedFileAdapterTest
│       ├── cleanup/                CleanupWorkingDirectoryAdapterTest
│       ├── download/               LocalFileSystemDownloaderAdapterTest
│       ├── ldap/                   LdapQueryAdapterTest, PersonLdapAttributesMapperTest
│       ├── notification/           LogNotificationAdapterTest
│       ├── persistence/            ImportDataHrMapperIT, InMemoryProcessReportRepositoryTest,
│       │                           JdbcDepartmentQueryAdapterTest, JdbcPersistenceAdapterTest,
│       │                           JdbcRoleQueryAdapterTest, JdbcTableMetadataAdapterTest
│       ├── reader/                 ExcelFileReaderAdapterTest
│       ├── report/                 CsvReportExporterAdapterTest, ExcelExecutionLogAdapterTest,
│       │                           ExcelReportStoreTest
│       └── yaml/                   SnakeYamlJobDefinitionAdapterTest
├── core/
│   ├── domain/factory/             DomainModelTest, JobFactoryTest
│   ├── services/                   ColumnAutoMapperTest, DefaultJobProcessorTest,
│   │                               DefaultMetricsCollectorTest, DepartmentUpdateServiceTest,
│   │                               ExecuteCommandTest, ExtractDataHrServiceTest,
│   │                               ImportDataHrServiceTest, IngestaServiceTest,
│   │                               RoleOwnershipServiceTest
│   └── utils/                      ColumnNormalizerTest
```

Test resources live in [src/test/resources/](../src/test/resources/):

- `application.yml` — test-only Spring properties (H2 in-memory + reduced logging).
- `jobs/` — YAML fixtures consumed by `SnakeYamlJobDefinitionAdapterTest` and end-to-end service tests.

## 2. Conventions

### Frameworks

- **JUnit 5** (`org.junit.jupiter`) for everything. No JUnit 4 left.
- **Mockito 5** via `@ExtendWith(MockitoExtension.class)` and `@Mock` field injection.
- **AssertJ** for assertions — `assertThat(value).isEqualTo(...)`, `.anyMatch(...)`, `.containsExactlyInAnyOrder(...)`.
- **Spring Boot Test** slices where they help:
  - `@JdbcTest` for the JDBC adapters ([`JdbcPersistenceAdapterTest`](../src/test/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/JdbcPersistenceAdapterTest.java), `JdbcTableMetadataAdapterTest`, `JdbcDepartmentQueryAdapterTest`, `JdbcRoleQueryAdapterTest`, `InMemoryProcessReportRepositoryTest`)
  - MyBatis test-starter for [`ImportDataHrMapperIT`](../src/test/java/es/ing/icenterprise/arthur/adapters/outbound/persistence/ImportDataHrMapperIT.java) (the `IT` suffix marks it as integration-level)

### Naming

- One test class per production class: `Foo.java` → `FooTest.java` (or `FooIT.java` for integration).
- Test methods describe behavior in past or present tense; use `@DisplayName` for the readable form when the method name is too compressed.
- Use `// ── Section ───` comment dividers inside large test classes (see [`DefaultJobProcessorTest`](../src/test/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessorTest.java)) — one section per step type / scenario group.

### Mocking style

- Configure baseline behavior with `lenient().when(...)` in `@BeforeEach` so individual tests can override without setup boilerplate.
- Use Mockito argument matchers (`any()`, `anyList()`, `anyMap()`, `eq()`, `isNull()`) consistently — mixing matchers and raw arguments throws.
- Avoid `verifyNoMoreInteractions` unless you specifically want to lock down the call pattern; it makes tests brittle.

### Fixtures

Helper methods live as private methods at the bottom of the test class. The pattern in `DefaultJobProcessorTest`:

- `givenFileData(Map<String, Object>...)` — primes the mocked `FileReaderPort.read` with a row stream.
- `buildJob(TaskType, Step...)` — builds a one-task `Job` with the supplied steps.
- `stepLogs(Job)` — flattens log entries from every level for assertions.

Replicate this pattern for new test classes when the same setup recurs more than twice.

### Integration vs unit

| Marker | Used for | Run by |
|---|---|---|
| `*Test.java` | Unit tests with mocks | `mvn test` |
| `*IT.java` | Integration tests with real DB / MyBatis | `mvn test` (currently — there is no `failsafe` split) |
| `@JdbcTest` | Spring slice: real `JdbcTemplate` against H2, schema bootstrapped in `@BeforeEach` | `mvn test` |

`@JdbcTest` slices roll back by default. The bisecting insert path uses `PROPAGATION_REQUIRES_NEW`, so `JdbcPersistenceAdapterTest` cleans state with its own committed transaction (`committedTx.executeWithoutResult(...)`) — copy that idiom whenever you test code that commits independently of the surrounding test transaction.

## 3. Running

```bash
# All tests, no coverage
mvn test

# All tests + JaCoCo coverage report at target/site/jacoco/index.html
mvn verify

# A single class
mvn test -Dtest=DefaultJobProcessorTest

# A single method
mvn test -Dtest=DefaultJobProcessorTest#lookupStepResolvesValuesAndAddsSummaryLog
```

Coverage is configured in `pom.xml` via the JaCoCo 0.8.12 plugin: `prepare-agent` runs in the default phase, and the `report` execution is bound to the `test` phase so a plain `mvn test` already produces the report.

## 4. Writing tests for new code

### New `StepType`

In [`DefaultJobProcessorTest`](../src/test/java/es/ing/icenterprise/arthur/core/services/DefaultJobProcessorTest.java), add a new section:

```java
// ── MY_NEW_STEP ──────────────────────────────────────────────────────────

@Test
@DisplayName("MY_NEW_STEP does X when Y")
void myNewStepDoesX() {
    Map<String, Object> rowData = new HashMap<>();
    rowData.put("Col", "value");
    givenFileData(rowData);

    Job job = buildJob(TaskType.TRANSFORMATION,
        new Step("my-step", StepType.MY_NEW_STEP, 1, Map.of("param", "x")));

    processor.process(List.of(job));

    // assert the data list now reflects the new step's effect
    // assert SUMMARY log was emitted
}
```

Test at least: the happy path, missing parameters (should log WARN, not throw), and any persistence interactions via mocks.

### New port / adapter

Two layers of tests:

1. **Port contract test** in `core/services/<Service>Test` — exercise the service with a mocked port and assert the orchestration.
2. **Adapter test** in `adapters/outbound/<adapter>/*Test.java` — exercise the real adapter. For JDBC: `@JdbcTest` slice, create the schema in `@BeforeEach`. For HTTP/LDAP: mock at the lowest level you can.

### New REST endpoint

Use Spring's `MockMvc` if you need request mapping coverage. Most existing controller tests construct the controller directly and call its methods — that's fine for thin controllers but does not cover the routing or HTTP plumbing. If routing matters, use `@WebMvcTest`.

## 5. What's not tested today

- **`SharepointDownloaderAdapter`** — stub; no test.
- **`SchedulerAdapter`** end-to-end — tested for the enabled/disabled branch only; the actual cron firing is Spring's responsibility.
- **Multi-DB compatibility for MyBatis** — `ImportDataHrMapperIT` runs against H2. Oracle paths are validated in environments, not in CI.

If you change behavior in these areas, prefer adding a test rather than relying on smoke tests in staging.

## 6. Test quality checklist

Before merging a PR that adds tests, verify:

- [ ] Test class mirrors production class location.
- [ ] `@DisplayName` describes the behavior, not the method (`"INSERT step inserts new rows when skipExisting is true"` rather than `"testInsertSkipExisting"`).
- [ ] Failures produce a useful AssertJ message — prefer `assertThat(list).extracting(Step::getName).containsExactly(...)` over `assertEquals(list.size(), 3)`.
- [ ] No `Thread.sleep`. Use `Awaitility` or callback verification instead.
- [ ] Mocks are scoped to the test; reset via `@BeforeEach` if shared.
- [ ] Integration tests clean their own data, especially anything written via `REQUIRES_NEW`.
