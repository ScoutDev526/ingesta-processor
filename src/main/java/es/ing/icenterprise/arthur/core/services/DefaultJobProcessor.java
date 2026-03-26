package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.domain.model.*;
import es.ing.icenterprise.arthur.core.domain.enums.*;
import es.ing.icenterprise.arthur.core.ports.outbound.FileReaderPort;
import es.ing.icenterprise.arthur.core.ports.outbound.PersistencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class DefaultJobProcessor implements JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobProcessor.class);
    private static final Pattern DATE_IN_FILENAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    private final List<FileReaderPort> fileReaders;
    private final PersistencePort persistencePort;
    private final ColumnAutoMapper columnAutoMapper;

    public DefaultJobProcessor(List<FileReaderPort> fileReaders,
                               PersistencePort persistencePort,
                               ColumnAutoMapper columnAutoMapper) {
        this.fileReaders = fileReaders;
        this.persistencePort = persistencePort;
        this.columnAutoMapper = columnAutoMapper;
    }

    @Override
    public void process(List<Job> jobs) {
        for (Job job : jobs) {
            if (job.getStatus() == Status.SKIPPED) {
                log.info("Skipping job: {}", job.getName());
                continue;
            }
            processJob(job);
        }
    }

    private void processJob(Job job) {
        job.start();
        log.info("Processing job: {}", job.getName());

        try {
            if (job.isProcessAllSheets()) {
                processJobAllSheets(job);
            } else {
                processJobSingleSheet(job);
            }
        } catch (Exception e) {
            log.error("Job '{}' failed: {}", job.getName(), e.getMessage(), e);
            job.addLog(LogEntry.error(job.getName(), "Job failed: " + e.getMessage(), e));
            job.complete(Status.FAILED);
        }
    }

    private void processJobSingleSheet(Job job) {
        // Read data from file
        List<Action> data = readFileData(job);

        // Extract Excel headers from first row
        List<String> excelHeaders = data.isEmpty()
                ? List.of()
                : new ArrayList<>(data.get(0).data().keySet());

        // Extract ingest date from filename (e.g. 2026-03-02-Name.xlsx), fallback to today
        LocalDate ingestDate = extractIngestDate(job.getFilePath());

        // Process each task
        boolean hasFailure = processAllTasks(job, data, excelHeaders, ingestDate, null);

        Status jobStatus = determineJobStatus(job, hasFailure);
        job.complete(jobStatus);
    }

    /**
     * Processes all sheets in the Excel file. Each sheet is treated as a separate table
     * whose name matches the sheet name. Tasks are executed once per sheet.
     */
    private void processJobAllSheets(Job job) {
        FileReaderPort reader = fileReaders.stream()
                .filter(r -> r.supports(job.getFileType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No reader found for file type: " + job.getFileType()));

        List<String> sheetNames = reader.getSheetNames(Path.of(job.getFilePath()));
        log.info("processAllSheets: found {} sheets in {}", sheetNames.size(), job.getFilePath());

        LocalDate ingestDate = extractIngestDate(job.getFilePath());
        boolean hasFailure = false;

        for (int i = 0; i < sheetNames.size(); i++) {
            String sheetName = sheetNames.get(i);
            log.info("Processing sheet {}/{}: '{}'", i + 1, sheetNames.size(), sheetName);

            Map<String, Object> readerParams = Map.of("sheetIndex", i);
            List<Action> data = new ArrayList<>();
            try (Stream<Map<String, Object>> stream = reader.read(Path.of(job.getFilePath()), readerParams)) {
                stream.forEach(row -> data.add(new Action(row)));
            }

            if (data.isEmpty()) {
                job.addLog(LogEntry.warn(job.getName(), "Sheet '" + sheetName + "' is empty, skipping"));
                continue;
            }

            List<String> excelHeaders = new ArrayList<>(data.get(0).data().keySet());
            job.addLog(LogEntry.info(job.getName(),
                    "Sheet '" + sheetName + "': read " + data.size() + " rows, columns: " + excelHeaders));

            hasFailure |= processAllTasks(job, data, excelHeaders, ingestDate, sheetName);
        }

        Status jobStatus = determineJobStatus(job, hasFailure);
        job.complete(jobStatus);
    }

    /**
     * Runs all tasks in the job against the given data.
     * @param sheetTableName if non-null, overrides the tableName parameter in persistence steps.
     * @return true if any task failed
     */
    private boolean processAllTasks(Job job, List<Action> data, List<String> excelHeaders,
                                    LocalDate ingestDate, String sheetTableName) {
        boolean hasFailure = false;
        for (Task task : job.getTasks()) {
            try {
                processTask(task, data, excelHeaders, ingestDate, sheetTableName);
            } catch (Exception e) {
                hasFailure = true;
                task.addLog(LogEntry.error(task.getName(), "Task failed: " + e.getMessage(), e));
                task.complete(Status.FAILED);
                if (task.isStopOnFailure()) {
                    job.addLog(LogEntry.error(job.getName(),
                            "Stopping job due to task failure: " + task.getName()));
                    break;
                }
            }
        }
        return hasFailure;
    }

    private List<Action> readFileData(Job job) {
        FileReaderPort reader = fileReaders.stream()
                .filter(r -> r.supports(job.getFileType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No reader found for file type: " + job.getFileType()));

        List<Action> actions = new ArrayList<>();
        Map<String, Object> readerParams = Map.of("sheetIndex", job.getSheetIndex());
        try (Stream<Map<String, Object>> stream = reader.read(Path.of(job.getFilePath()), readerParams)) {
            stream.forEach(row -> actions.add(new Action(row)));
        }

        job.getMetrics().addCustomMetric("totalRowsRead", actions.size());
        job.addLog(LogEntry.info(job.getName(), "Read " + actions.size() + " rows from file"));
        return actions;
    }

    private void processTask(Task task, List<Action> data, List<String> excelHeaders,
                             LocalDate ingestDate, String sheetTableName) {
        task.start();
        log.info("Processing task: {} (type: {})", task.getName(), task.getTaskType());

        switch (task.getTaskType()) {
            case TRANSFORMATION -> processTransformationTask(task, data);
            case PERSISTENCE -> processPersistenceTask(task, data, excelHeaders, ingestDate, sheetTableName);
        }

        if (task.getStatus() == Status.RUNNING) {
            task.complete(Status.SUCCESS);
        }
    }

    // ======================== TRANSFORMATION ========================

    private void processTransformationTask(Task task, List<Action> data) {
        for (Step step : task.getSteps()) {
            step.start();
            try {
                applyTransformation(step, data);
                step.getMetrics().incrementProcessed(data.size());
                step.complete(Status.SUCCESS);
            } catch (Exception e) {
                step.addLog(LogEntry.error(step.getName(), "Step failed: " + e.getMessage(), e));
                step.complete(Status.FAILED);
                throw e;
            }
        }
        task.getMetrics().incrementProcessed(data.size());
    }

    private void applyTransformation(Step step, List<Action> data) {
        log.debug("Applying transformation: {}", step.getStepType());

        switch (step.getStepType()) {
            case TRIM -> data.forEach(action ->
                    action.data().replaceAll((k, v) -> v instanceof String s ? s.trim() : v));
            case UPPERCASE -> data.forEach(action ->
                    action.data().replaceAll((k, v) -> v instanceof String s ? s.toUpperCase() : v));
            case CONCATENATE -> {
                @SuppressWarnings("unchecked")
                List<String> sources = (List<String>) step.getParameters().get("sourceColumns");
                String targetColumn = (String) step.getParameters().get("targetColumn");
                String separator = (String) step.getParameters().getOrDefault("separator", "");

                if (targetColumn == null || sources == null || sources.isEmpty()) {
                    step.addLog(LogEntry.warn(step.getName(),
                            "CONCATENATE step missing required parameters: 'targetColumn' and 'sourceColumns'"));
                    break;
                }

                data.forEach(action -> {
                    String concatenated = sources.stream()
                            .map(col -> {
                                Object val = action.get(col);
                                return val != null ? val.toString() : "";
                            })
                            .filter(s -> !s.isBlank())
                            .collect(Collectors.joining(separator));
                    action.data().put(targetColumn, concatenated);
                });

                step.addLog(LogEntry.info(step.getName(),
                        "Concatenated sourceColumns=" + sources + " → '" + targetColumn + "' for " + data.size() + " rows"));
            }
            case DEDUPLICATE -> {
                String keyColumn = (String) step.getParameters().get("keyColumn");
                if (keyColumn == null) {
                    step.addLog(LogEntry.warn(step.getName(),
                            "DEDUPLICATE step missing required parameter: 'keyColumn'"));
                    break;
                }
                int before = data.size();
                Set<Object> seen = new LinkedHashSet<>();
                data.removeIf(action -> !seen.add(action.get(keyColumn)));
                int removed = before - data.size();
                step.addLog(LogEntry.info(step.getName(),
                        "Deduplicated on '" + keyColumn + "': removed " + removed + " duplicates, " + data.size() + " remaining"));
            }
            case FILTER_NULL -> {
                String column = (String) step.getParameters().get("column");
                if (column == null) {
                    step.addLog(LogEntry.warn(step.getName(),
                            "FILTER_NULL step missing required parameter: 'column'"));
                    break;
                }
                int before = data.size();
                data.removeIf(action -> {
                    Object val = action.get(column);
                    return val == null || val.toString().isBlank();
                });
                int removed = before - data.size();
                step.addLog(LogEntry.info(step.getName(),
                        "Filtered on '" + column + "': removed " + removed + " null/blank rows, "
                        + data.size() + " remaining"));
            }
            default -> step.addLog(LogEntry.warn(step.getName(),
                    "Unknown transformation type: " + step.getStepType()));
        }
    }

    // ======================== PERSISTENCE ========================

    private void processPersistenceTask(Task task, List<Action> data, List<String> excelHeaders,
                                       LocalDate ingestDate, String sheetTableName) {
        for (Step step : task.getSteps()) {
            step.start();
            try {
                executePersistenceStep(step, data, excelHeaders, ingestDate, sheetTableName);
                step.complete(Status.SUCCESS);
            } catch (Exception e) {
                step.addLog(LogEntry.error(step.getName(), "Persistence step failed: " + e.getMessage(), e));
                step.complete(Status.FAILED);
                throw e;
            }
        }
    }

    private void executePersistenceStep(Step step, List<Action> data, List<String> excelHeaders,
                                       LocalDate ingestDate, String sheetTableName) {
        log.debug("Executing persistence step: {}", step.getStepType());

        Map<String, Object> params = new HashMap<>(step.getParameters());
        params.put("_ingestDate", ingestDate);

        // When processing all sheets, override tableName with the sheet name
        if (sheetTableName != null) {
            params.put("tableName", sheetTableName);
        }

        switch (step.getStepType()) {
            case TRUNCATE -> {
                persistencePort.truncate(params);
                step.addLog(LogEntry.info(step.getName(),
                        "Table '" + params.get("tableName") + "' truncated"));
            }
            case INSERT -> {
                // Resolve mappings: auto-map Excel headers → DB columns
                List<DatabaseMapping> mappings = resolveMappings(params, excelHeaders);

                String tableName = (String) params.getOrDefault("tableName", "ingesta_data");
                String schema = (String) params.get("schema");
                String idColumn = (String) params.getOrDefault("idColumn", "ID");
                boolean skipExisting = Boolean.TRUE.equals(params.get("skipExisting"));
                boolean upsertMode = Boolean.TRUE.equals(params.get("upsertMode"));

                List<Action> toInsert = data;
                List<Action> toUpdate = List.of();

                if (skipExisting || upsertMode) {
                    String excelIdCol = findExcelColumnForDbColumn(idColumn, mappings);
                    Set<Object> existingIds = persistencePort.loadExistingIds(tableName, schema, idColumn);

                    List<Action> newRows = new ArrayList<>();
                    List<Action> existingRows = new ArrayList<>();
                    for (Action action : data) {
                        Object idVal = action.get(excelIdCol);
                        if (idVal != null && existingIds.contains(idVal.toString())) {
                            existingRows.add(action);
                        } else if (idVal != null) {
                            newRows.add(action);
                        }
                    }

                    toInsert = newRows;
                    if (upsertMode) {
                        toUpdate = existingRows;
                    }
                    step.addLog(LogEntry.info(step.getName(),
                            toInsert.size() + " new rows to insert, "
                            + existingRows.size() + " existing rows"
                            + (upsertMode ? " to update" : " skipped")));
                }

                // INSERT new rows
                int batchSize = ((Number) params.getOrDefault("_batchSize", 500)).intValue();
                int totalInserted = toInsert.size();
                for (int i = 0; i < totalInserted; i += batchSize) {
                    List<Action> chunk = toInsert.subList(i, Math.min(i + batchSize, totalInserted));
                    persistencePort.insertData(chunk, mappings, params);
                }

                // UPDATE existing rows (upsert mode)
                int totalUpdated = toUpdate.size();
                if (totalUpdated > 0) {
                    for (int i = 0; i < totalUpdated; i += batchSize) {
                        List<Action> chunk = toUpdate.subList(i, Math.min(i + batchSize, totalUpdated));
                        persistencePort.updateData(chunk, mappings, params, idColumn);
                    }
                }

                step.getMetrics().incrementProcessed(totalInserted + totalUpdated);
                step.addLog(LogEntry.info(step.getName(),
                        "Inserted " + totalInserted + ", updated " + totalUpdated
                        + " records (" + mappings.size() + " columns mapped)"));
            }
            case SELECT -> {
                Object result = persistencePort.check(null, params);
                step.addLog(LogEntry.info(step.getName(), "Check completed"));
            }
            case LINK_PARENT -> executeLinkParent(step, data, params);
            case VALIDATE_REFERENCE -> executeValidateReference(step, data, params);
            default -> step.addLog(LogEntry.warn(step.getName(),
                    "Unknown persistence type: " + step.getStepType()));
        }
    }

    /**
     * Resolves column mappings from step parameters.
     * If autoMap is enabled (default), uses ColumnAutoMapper to match Excel headers to DB columns.
     * Explicit mappings from YAML are always included and take priority.
     */
    @SuppressWarnings("unchecked")
    private List<DatabaseMapping> resolveMappings(Map<String, Object> params, List<String> excelHeaders) {
        String tableName = (String) params.getOrDefault("tableName", "ingesta_data");
        String schema = (String) params.get("schema");
        boolean autoMap = (boolean) params.getOrDefault("autoMap", true);

        // Parse explicit mappings from YAML parameters
        List<DatabaseMapping> explicitMappings = parseExplicitMappings(params);

        if (autoMap) {
            return columnAutoMapper.resolve(excelHeaders, tableName, schema, explicitMappings);
        }

        // autoMap disabled: use only explicit mappings
        return explicitMappings;
    }

    /**
     * Parses the "mappings" list from step parameters into DatabaseMapping objects.
     */
    @SuppressWarnings("unchecked")
    private List<DatabaseMapping> parseExplicitMappings(Map<String, Object> params) {
        Object rawMappings = params.get("mappings");
        if (rawMappings == null) return List.of();

        if (rawMappings instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> {
                        Map<String, Object> m = (Map<String, Object>) item;
                        String excelColumn = (String) m.get("excelColumn");
                        String dbColumn = (String) m.get("dbColumn");
                        String autoGenerate = (String) m.get("autoGenerate");
                        List<String> concatenate = (List<String>) m.get("concatenate");
                        String separator = (String) m.getOrDefault("separator", "");
                        return new DatabaseMapping(excelColumn, dbColumn, autoGenerate, concatenate, separator);
                    })
                    .toList();
        }

        return List.of();
    }

    private void executeValidateReference(Step step, List<Action> data, Map<String, Object> params) {
        String fieldColumn       = (String) params.get("fieldColumn");
        String referenceTable    = (String) params.get("referenceTable");
        String referenceIdColumn = (String) params.getOrDefault("referenceIdColumn", "ID");
        String timestampColumn   = (String) params.getOrDefault("timestampColumn", "TIMESTAMP");
        String idColumn          = (String) params.get("idColumn");
        String schema            = (String) params.get("schema");
        String etlLogTable       = (String) params.get("etlLogTable");
        String currentEntityType = (String) params.getOrDefault("currentEntityType", "Unknown");
        LocalDate ingestDate     = (LocalDate) params.getOrDefault("_ingestDate", LocalDate.now());

        // Load all valid reference IDs in one query (cache)
        Set<Object> validIds = persistencePort.loadReferenceIds(
                referenceTable, schema, referenceIdColumn, timestampColumn, ingestDate);
        log.debug("VALIDATE_REFERENCE: loaded {} valid IDs from {}", validIds.size(), referenceTable);

        int cleared = 0;
        for (Action action : data) {
            Object fieldValue = action.get(fieldColumn);
            if (fieldValue == null || fieldValue.toString().isBlank()) continue;

            if (!validIds.contains(fieldValue)) {
                String currentId = idColumn != null ? String.valueOf(action.get(idColumn)) : "?";
                log.warn("VALIDATE_REFERENCE [{}]: value '{}' not found in '{}'. Record ID='{}'",
                        fieldColumn, fieldValue, referenceTable, currentId);
                insertEtlLog(etlLogTable, schema,
                        "Unknown", fieldColumn, fieldValue.toString(),
                        currentEntityType, currentId, "loading data with empty value", ingestDate);
                action.data().put(fieldColumn, "");
                cleared++;
            }
        }

        step.getMetrics().incrementProcessed(data.size());
        step.addLog(LogEntry.info(step.getName(), String.format(
                "VALIDATE_REFERENCE '%s' → '%s.%s': %d invalid values cleared",
                fieldColumn, referenceTable, referenceIdColumn, cleared)));
    }

    @SuppressWarnings("unchecked")
    private void executeLinkParent(Step step, List<Action> data, Map<String, Object> params) {
        String idColumn           = (String) params.get("idColumn");
        String parentPathColumn   = (String) params.get("parentPathColumn");
        String parentObjColumn    = (String) params.get("parentObjectsColumn");
        String parentTypeColumn   = (String) params.get("parentTypeColumn");
        String separator          = (String) params.getOrDefault("separator", "/");
        String checkIdColumn      = (String) params.getOrDefault("checkIdColumn", "ID");
        String id1Column          = (String) params.getOrDefault("id1Column", "ID_1");
        String id2Column          = (String) params.getOrDefault("id2Column", "ID_2");
        String timestampColumn    = (String) params.getOrDefault("timestampColumn", "TIMESTAMP");
        String schema             = (String) params.get("schema");
        String etlLogTable        = (String) params.get("etlLogTable");
        String currentEntityType  = (String) params.getOrDefault("currentEntityType", "Unknown");
        LocalDate ingestDate      = (LocalDate) params.getOrDefault("_ingestDate", LocalDate.now());

        List<Map<String, Object>> rules = (List<Map<String, Object>>) params.get("rules");

        int linked = 0, notFound = 0, unknownType = 0;

        for (Action action : data) {
            Object parentTypeVal = action.get(parentTypeColumn);
            if (parentTypeVal == null || parentTypeVal.toString().isBlank()) continue;

            String parentType = parentTypeVal.toString().trim();
            String parentId   = buildParentId(action, parentPathColumn, parentObjColumn, separator);
            String currentId  = String.valueOf(action.get(idColumn));

            Map<String, Object> rule = findRule(rules, parentType);

            if (rule != null) {
                String checkTable       = (String) rule.get("checkTable");
                String relationTable    = (String) rule.get("relationTable");
                String parentEntityType = (String) rule.getOrDefault("parentEntityType", checkTable);

                boolean exists = persistencePort.checkExists(
                        checkTable, schema, checkIdColumn, parentId, timestampColumn, ingestDate);

                if (exists) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(id1Column, currentId);
                    row.put(id2Column, parentId);
                    row.put(timestampColumn, ingestDate);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> extraColumns = (Map<String, Object>) rule.get("extraColumns");
                    if (extraColumns != null) {
                        row.putAll(extraColumns);
                    }
                    persistencePort.insertRow(relationTable, schema, row);
                    linked++;
                } else {
                    log.warn("LINK_PARENT [{}]: parent '{}' not found in '{}'. Record ID='{}'",
                            parentType, parentId, checkTable, currentId);
                    insertEtlLog(etlLogTable, schema, parentEntityType, parentId,
                            currentEntityType, currentId, "Unknown", "relationship not loaded", ingestDate);
                    notFound++;
                }
            } else {
                log.warn("LINK_PARENT: unknown parentType='{}'. Record ID='{}'", parentType, currentId);
                insertEtlLog(etlLogTable, schema, "Unknown", parentType,
                        currentEntityType, currentId, "Unknown", "relationship not loaded", ingestDate);
                unknownType++;
            }
        }

        step.addLog(LogEntry.info(step.getName(), String.format(
                "LINK_PARENT: %d linked, %d parent not found, %d unknown parentType",
                linked, notFound, unknownType)));
    }

    private void insertEtlLog(String etlLogTable, String schema,
                              String whereA, String a, String whereB,
                              String b, String cause, String action, LocalDate date) {
        if (etlLogTable == null) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("WHERE_A", whereA);
        row.put("A", a);
        row.put("WHERE_B", whereB);
        row.put("B", b);
        row.put("CAUSE", cause);
        row.put("ACTION", action);
        row.put("TIMESTAMP", date);
        persistencePort.insertRow(etlLogTable, schema, row);
    }

    private String buildParentId(Action action, String pathColumn, String objectsColumn, String separator) {
        Object path    = action.get(pathColumn);
        Object objects = action.get(objectsColumn);
        return (path != null ? path.toString() : "") + separator + (objects != null ? objects.toString() : "");
    }

    private String findExcelColumnForDbColumn(String dbColumn, List<DatabaseMapping> mappings) {
        return mappings.stream()
                .filter(m -> dbColumn.equalsIgnoreCase(m.dbColumn()) && m.excelColumn() != null)
                .map(DatabaseMapping::excelColumn)
                .findFirst()
                .orElse(dbColumn);
    }

    private Map<String, Object> findRule(List<Map<String, Object>> rules, String parentType) {
        if (rules == null) return null;
        return rules.stream()
                .filter(r -> parentType.equals(r.get("parentType")))
                .findFirst()
                .orElse(null);
    }

    /**
     * Extracts the ingest date from the filename (pattern YYYY-MM-DD anywhere in the name).
     * Falls back to today if no date is found or it cannot be parsed.
     * Examples: "2026-03-02-Audits.xlsx" → 2026-03-02
     *           "Audits.xlsx"            → LocalDate.now()
     */
    private LocalDate extractIngestDate(String filePath) {
        String fileName = Path.of(filePath).getFileName().toString();
        java.util.regex.Matcher matcher = DATE_IN_FILENAME.matcher(fileName);
        if (matcher.find()) {
            try {
                LocalDate date = LocalDate.parse(matcher.group(1));
                log.info("Ingest date extracted from filename '{}': {}", fileName, date);
                return date;
            } catch (Exception e) {
                log.warn("Found '{}' in filename '{}' but could not parse as date, using today",
                        matcher.group(1), fileName);
            }
        } else {
            log.info("No date pattern found in filename '{}', using today as ingest date", fileName);
        }
        return LocalDate.now();
    }

    private Status determineJobStatus(Job job, boolean hasFailure) {
        if (!hasFailure) return Status.SUCCESS;

        boolean allTasksFailed = job.getTasks().stream()
                .allMatch(t -> t.getStatus() == Status.FAILED);
        return allTasksFailed ? Status.FAILED : Status.PARTIAL;
    }
}
