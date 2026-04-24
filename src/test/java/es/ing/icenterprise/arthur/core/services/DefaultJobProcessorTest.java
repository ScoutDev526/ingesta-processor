package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.domain.enums.*;
import es.ing.icenterprise.arthur.core.domain.model.*;
import es.ing.icenterprise.arthur.core.ports.outbound.FileReaderPort;
import es.ing.icenterprise.arthur.core.ports.outbound.InsertResult;
import es.ing.icenterprise.arthur.core.ports.outbound.PersistencePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultJobProcessorTest {

    @Mock private FileReaderPort fileReader;
    @Mock private PersistencePort persistencePort;
    @Mock private ColumnAutoMapper columnAutoMapper;

    private DefaultJobProcessor processor;

    @BeforeEach
    void setUp() {
        lenient().when(fileReader.supports(any())).thenReturn(true);
        // Default: every chunk inserted successfully (tests can override per-call).
        lenient().when(persistencePort.insertData(anyList(), anyList(), anyMap()))
                .thenAnswer(inv -> {
                    List<?> chunk = inv.getArgument(0);
                    return new InsertResult(chunk.size(), 0);
                });
        processor = new DefaultJobProcessor(List.of(fileReader), persistencePort, columnAutoMapper);
    }

    // ── LOOKUP ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LOOKUP step resolves values via lookupValues() and adds SUMMARY log")
    void lookupStepResolvesValuesAndAddsSummaryLog() {
        Map<String, Object> rowData = new HashMap<>();
        rowData.put("Email", "alice@example.com");
        givenFileData(rowData);

        when(persistencePort.lookupValues(eq("hr"), isNull(), eq("mail"), eq("id"), isNull()))
                .thenReturn(Map.of("alice@example.com", "CK123"));

        Job job = buildJob(TaskType.TRANSFORMATION,
                new Step("resolve", StepType.LOOKUP, 1, Map.of(
                        "sourceColumn", "Email",
                        "targetColumn", "CK",
                        "referenceTable", "hr",
                        "referenceKeyColumn", "mail",
                        "referenceValueColumn", "id"
                )));

        processor.process(List.of(job));

        verify(persistencePort).lookupValues("hr", null, "mail", "id", null);
        assertThat(stepLogs(job)).anyMatch(e -> e.getLevel() == LogLevel.SUMMARY);
        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
    }

    @Test
    @DisplayName("LOOKUP step sets null for sentinel values and SUMMARY mentions 'set to null'")
    void lookupStepSetsNullForSentinelValues() {
        Map<String, Object> rowData = new HashMap<>();
        rowData.put("Owner", "Unknown");
        givenFileData(rowData);

        when(persistencePort.lookupValues(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("alice@example.com", "CK123"));

        Job job = buildJob(TaskType.TRANSFORMATION,
                new Step("resolve", StepType.LOOKUP, 1, Map.of(
                        "sourceColumn", "Owner",
                        "targetColumn", "Owner_CK",
                        "referenceTable", "hr",
                        "referenceKeyColumn", "mail",
                        "referenceValueColumn", "id",
                        "nullValues", List.of("Unknown", "")
                )));

        processor.process(List.of(job));

        LogEntry summaryLog = stepLogs(job).stream()
                .filter(e -> e.getLevel() == LogLevel.SUMMARY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No SUMMARY log found"));
        assertThat(summaryLog.getMessage()).contains("set to null");
    }

    // ── LINK_PARENT ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("LINK_PARENT adds TRACE (not WARN) when parent is not found")
    void linkParentNotFoundAddsTraceNotWarn() {
        Map<String, Object> rowData = new HashMap<>();
        rowData.put("ID", "risk-1");
        rowData.put("ParentType", "Control");
        rowData.put("ParentPath", "path");
        rowData.put("ParentObjects", "ctrl-1");
        givenFileData(rowData);

        when(persistencePort.checkExists(eq("CONTROL"), any(), anyString(), any(), anyString(), any()))
                .thenReturn(false);

        Job job = buildJob(TaskType.PERSISTENCE,
                new Step("link", StepType.LINK_PARENT, 1, Map.of(
                        "idColumn", "ID",
                        "parentTypeColumn", "ParentType",
                        "parentPathColumn", "ParentPath",
                        "parentObjectsColumn", "ParentObjects",
                        "rules", List.of(Map.of(
                                "parentType", "Control",
                                "checkTable", "CONTROL",
                                "relationTable", "RISK_CONTROL"
                        ))
                )));

        processor.process(List.of(job));

        List<LogEntry> logs = stepLogs(job);
        assertThat(logs).anyMatch(e -> e.getLevel() == LogLevel.TRACE);
        assertThat(logs).anyMatch(e -> e.getLevel() == LogLevel.SUMMARY);
        // Must NOT have a WARN log that says "not found"
        assertThat(logs).noneMatch(e ->
                e.getLevel() == LogLevel.WARN && e.getMessage().toLowerCase().contains("not found"));
    }

    @Test
    @DisplayName("LINK_PARENT inserts a row and adds SUMMARY log when parent is found")
    void linkParentFoundInsertsRow() {
        Map<String, Object> rowData = new HashMap<>();
        rowData.put("ID", "risk-1");
        rowData.put("ParentType", "Control");
        rowData.put("ParentPath", "path");
        rowData.put("ParentObjects", "ctrl-1");
        givenFileData(rowData);

        when(persistencePort.checkExists(eq("CONTROL"), any(), anyString(), any(), anyString(), any()))
                .thenReturn(true);

        Job job = buildJob(TaskType.PERSISTENCE,
                new Step("link", StepType.LINK_PARENT, 1, Map.of(
                        "idColumn", "ID",
                        "parentTypeColumn", "ParentType",
                        "parentPathColumn", "ParentPath",
                        "parentObjectsColumn", "ParentObjects",
                        "rules", List.of(Map.of(
                                "parentType", "Control",
                                "checkTable", "CONTROL",
                                "relationTable", "RISK_CONTROL"
                        ))
                )));

        processor.process(List.of(job));

        verify(persistencePort).insertRow(eq("RISK_CONTROL"), any(), anyMap());
        assertThat(stepLogs(job)).anyMatch(e -> e.getLevel() == LogLevel.SUMMARY);
    }

    // ── VALIDATE_REFERENCE ───────────────────────────────────────────────────

    @Test
    @DisplayName("VALIDATE_REFERENCE adds TRACE log when value is not in reference set")
    void validateReferenceNotFoundAddsTraceLog() {
        Map<String, Object> rowData = new HashMap<>();
        rowData.put("ID", "row-1");
        rowData.put("DOMAIN_ID", "unknown-domain");
        givenFileData(rowData);

        when(persistencePort.loadReferenceIds(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(Set.of("domain-1", "domain-2")); // "unknown-domain" absent

        Job job = buildJob(TaskType.PERSISTENCE,
                new Step("validate", StepType.VALIDATE_REFERENCE, 1, Map.of(
                        "fieldColumn", "DOMAIN_ID",
                        "referenceTable", "DOMAIN",
                        "idColumn", "ID"
                )));

        processor.process(List.of(job));

        List<LogEntry> logs = stepLogs(job);
        assertThat(logs).anyMatch(e -> e.getLevel() == LogLevel.TRACE);
        assertThat(logs).anyMatch(e -> e.getLevel() == LogLevel.SUMMARY);
    }

    // ── INSERT ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("INSERT step calls insertData() and adds SUMMARY log with record count")
    void insertStepAddsSummaryLog() {
        Map<String, Object> rowData = new HashMap<>();
        rowData.put("Name", "test-name");
        givenFileData(rowData);

        when(columnAutoMapper.resolve(anyList(), anyString(), any(), anyList()))
                .thenReturn(List.of(new DatabaseMapping("Name", "NAME")));

        Job job = buildJob(TaskType.PERSISTENCE,
                new Step("insert", StepType.INSERT, 1, Map.of(
                        "tableName", "TEST_TABLE",
                        "autoMap", true
                )));

        processor.process(List.of(job));

        verify(persistencePort).insertData(anyList(), anyList(), anyMap());
        assertThat(stepLogs(job)).anyMatch(e -> e.getLevel() == LogLevel.SUMMARY);
        assertThat(job.getStatus()).isEqualTo(Status.SUCCESS);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void givenFileData(Map<String, Object> rowData) {
        lenient().when(fileReader.read(any(Path.class)))
                .thenAnswer(inv -> Stream.of(new HashMap<>(rowData)));
        lenient().when(fileReader.read(any(Path.class), anyMap()))
                .thenAnswer(inv -> Stream.of(new HashMap<>(rowData)));
    }

    private Job buildJob(TaskType taskType, Step step) {
        Task task = new Task("task", taskType, 1, false);
        task.addStep(step);
        Job job = new Job("test-job", "/data/test.xlsx", FileType.EXCEL);
        job.addTask(task);
        return job;
    }

    private List<LogEntry> stepLogs(Job job) {
        return job.getTasks().get(0).getSteps().get(0).getLogs();
    }
}
