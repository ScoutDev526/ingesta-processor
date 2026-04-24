package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import es.ing.icenterprise.arthur.core.domain.model.Action;
import es.ing.icenterprise.arthur.core.domain.model.DatabaseMapping;
import es.ing.icenterprise.arthur.core.ports.outbound.InsertResult;
import es.ing.icenterprise.arthur.core.ports.outbound.PersistencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Component
public class JdbcPersistenceAdapter implements PersistencePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcPersistenceAdapter.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcPersistenceAdapter(JdbcTemplate jdbcTemplate, PlatformTransactionManager txManager) {
        this.jdbcTemplate = jdbcTemplate;
        // REQUIRES_NEW: each batch attempt is its own short-lived transaction so a
        // failed chunk can be rolled back cleanly before the bisected retries run.
        this.transactionTemplate = new TransactionTemplate(txManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public InsertResult insertData(List<Action> data, List<DatabaseMapping> mappings, Map<String, Object> parameters) {
        String tableName = (String) parameters.getOrDefault("tableName", "ingesta_data");
        log.info("Inserting {} records into table: {} using {} mappings", data.size(), tableName, mappings.size());

        if (data.isEmpty() || mappings.isEmpty()) return InsertResult.EMPTY;

        // Build column list from mappings
        List<String> dbColumns = mappings.stream()
                .map(DatabaseMapping::dbColumn)
                .toList();

        // Build placeholders: ? for normal/concat, function call for auto-generated
        List<String> placeholders = mappings.stream()
                .map(this::buildPlaceholder)
                .toList();

        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                tableName,
                String.join(", ", dbColumns),
                String.join(", ", placeholders));

        log.debug("Generated SQL: {}", sql);

        // Resolve ingest date: from filename (passed via params) or fallback to today
        LocalDate ingestDate = (LocalDate) parameters.getOrDefault("_ingestDate", LocalDate.now());
        Timestamp ingestTimestamp = Timestamp.valueOf(ingestDate.atStartOfDay());

        List<Object[]> batchArgs = data.stream()
                .map(action -> buildRowArgs(action, mappings, ingestTimestamp))
                .toList();

        InsertResult result = batchInsertBisect(sql, batchArgs, tableName);
        log.info("Insert into {} finished: {} inserted, {} failed", tableName, result.inserted(), result.failed());
        return result;
    }

    /**
     * Attempts a batch insert; on failure, splits the chunk in half and retries
     * each half recursively. When the recursion bottoms out at a single failing
     * row, that row is logged and counted as failed. This isolates bad rows and
     * lets the rest of the batch through.
     */
    private InsertResult batchInsertBisect(String sql, List<Object[]> batchArgs, String tableName) {
        if (batchArgs.isEmpty()) return InsertResult.EMPTY;
        try {
            return transactionTemplate.execute(status -> {
                jdbcTemplate.batchUpdate(sql, batchArgs);
                return new InsertResult(batchArgs.size(), 0);
            });
        } catch (DataAccessException e) {
            if (batchArgs.size() == 1) {
                log.warn("Skipping bad row for {}: values={} error={}",
                        tableName, Arrays.toString(batchArgs.get(0)), rootCauseMessage(e));
                return new InsertResult(0, 1);
            }
            int mid = batchArgs.size() / 2;
            InsertResult left = batchInsertBisect(sql, batchArgs.subList(0, mid), tableName);
            InsertResult right = batchInsertBisect(sql, batchArgs.subList(mid, batchArgs.size()), tableName);
            return left.plus(right);
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }

    @Override
    public Object check(Object data, Map<String, Object> parameters) {
        String query = (String) parameters.getOrDefault("query", "SELECT 1");
        log.info("Executing check query: {}", query);
        return jdbcTemplate.queryForObject(query, Object.class);
    }

    @Override
    public void truncate(Map<String, Object> parameters) {
        String tableName = (String) parameters.getOrDefault("tableName", "ingesta_data");
        String timestampColumn = (String) parameters.get("timestampColumn");

        if (timestampColumn != null) {
            LocalDate ingestDate = (LocalDate) parameters.getOrDefault("_ingestDate", LocalDate.now());
            Timestamp today = Timestamp.valueOf(ingestDate.atStartOfDay());
            log.info("Deleting records from {} where {} = {}", tableName, timestampColumn, today);
            int deleted = jdbcTemplate.update(
                    "DELETE FROM " + tableName + " WHERE " + timestampColumn + " = ?", today);
            log.info("Deleted {} records from {}", deleted, tableName);
        } else {
            log.info("Truncating table: {}", tableName);
            jdbcTemplate.execute("TRUNCATE TABLE " + tableName);
        }
    }

    /**
     * Builds the placeholder for a mapping in the VALUES clause.
     * Normal and concatenated fields use ?, auto-generated use ? too
     * (we resolve values in Java, not DB functions, for portability).
     */
    private String buildPlaceholder(DatabaseMapping mapping) {
        return "?";
    }

    /**
     * Builds the argument array for a single row based on the mappings.
     */
    private Object[] buildRowArgs(Action action, List<DatabaseMapping> mappings, Timestamp ingestTimestamp) {
        return mappings.stream()
                .map(mapping -> resolveValue(action, mapping, ingestTimestamp))
                .toArray();
    }

    /**
     * Resolves the value for a single mapping from an Action row.
     */
    private Object resolveValue(Action action, DatabaseMapping mapping, Timestamp ingestTimestamp) {
        if (mapping.isAutoGenerated()) {
            return resolveAutoGenerated(mapping, ingestTimestamp);
        }

        if (mapping.isConcatenated()) {
            return resolveConcatenated(action, mapping);
        }

        // Normal mapping: get value from Excel by original header name
        return action.get(mapping.excelColumn());
    }

    private Object resolveAutoGenerated(DatabaseMapping mapping, Timestamp ingestTimestamp) {
        return switch (mapping.autoGenerate().toUpperCase()) {
            case "TIMESTAMP" -> ingestTimestamp;
            case "UUID" -> UUID.randomUUID().toString();
            default -> {
                log.warn("Unknown autoGenerate type: {}", mapping.autoGenerate());
                yield null;
            }
        };
    }

    @Override
    public boolean checkExists(String tableName, String schema, String idColumn, Object idValue,
                               String timestampColumn, LocalDate date) {
        String fullTable = schema != null ? schema + "." + tableName : tableName;
        String sql = "SELECT COUNT(*) FROM " + fullTable
                + " WHERE " + idColumn + " = ? AND " + timestampColumn + " = ?";
        Timestamp ts = Timestamp.valueOf(date.atStartOfDay());
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, idValue, ts);
        return count != null && count > 0;
    }

    @Override
    public void insertRow(String tableName, String schema, Map<String, Object> columnValues) {
        String fullTable = schema != null ? schema + "." + tableName : tableName;
        List<String> columns = new ArrayList<>(columnValues.keySet());
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + fullTable + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")";

        Object[] args = columns.stream()
                .map(col -> {
                    Object v = columnValues.get(col);
                    return v instanceof LocalDate ld ? Timestamp.valueOf(ld.atStartOfDay()) : v;
                })
                .toArray();

        jdbcTemplate.update(sql, args);
        log.debug("Inserted row into {}: {}", fullTable, columnValues);
    }

    @Override
    public Set<Object> loadReferenceIds(String tableName, String schema, String idColumn,
                                        String timestampColumn, LocalDate date) {
        String fullTable = schema != null ? schema + "." + tableName : tableName;
        String sql = "SELECT " + idColumn + " FROM " + fullTable + " WHERE " + timestampColumn + " = ?";
        Timestamp ts = Timestamp.valueOf(date.atStartOfDay());
        List<Object> ids = jdbcTemplate.queryForList(sql, Object.class, ts);
        return new HashSet<>(ids);
    }

    @Override
    public Set<Object> loadExistingIds(String tableName, String schema, String idColumn) {
        String fullTable = schema != null ? schema + "." + tableName : tableName;
        String sql = "SELECT " + idColumn + " FROM " + fullTable;
        List<Object> ids = jdbcTemplate.queryForList(sql, Object.class);
        return new HashSet<>(ids);
    }

    @Override
    public void updateData(List<Action> data, List<DatabaseMapping> mappings,
                           Map<String, Object> parameters, String idColumn) {
        String tableName = (String) parameters.getOrDefault("tableName", "ingesta_data");
        log.info("Updating {} records in table: {} by idColumn: {}", data.size(), tableName, idColumn);

        if (data.isEmpty() || mappings.isEmpty()) return;

        // Find the mapping for the ID column (used in WHERE clause)
        DatabaseMapping idMapping = mappings.stream()
                .filter(m -> idColumn.equalsIgnoreCase(m.dbColumn()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No mapping found for idColumn: " + idColumn));

        // SET columns = all mappings except the ID column
        List<DatabaseMapping> setCols = mappings.stream()
                .filter(m -> !idColumn.equalsIgnoreCase(m.dbColumn()))
                .toList();

        if (setCols.isEmpty()) {
            log.warn("No columns to update (only ID mapped) for table {}", tableName);
            return;
        }

        String setClause = setCols.stream()
                .map(m -> m.dbColumn() + " = ?")
                .collect(Collectors.joining(", "));

        String sql = String.format("UPDATE %s SET %s WHERE %s = ?", tableName, setClause, idColumn);
        log.debug("Generated UPDATE SQL: {}", sql);

        LocalDate ingestDate = (LocalDate) parameters.getOrDefault("_ingestDate", LocalDate.now());
        Timestamp ingestTimestamp = Timestamp.valueOf(ingestDate.atStartOfDay());

        List<Object[]> batchArgs = data.stream()
                .map(action -> {
                    List<Object> args = new ArrayList<>();
                    // SET values
                    for (DatabaseMapping m : setCols) {
                        args.add(resolveValue(action, m, ingestTimestamp));
                    }
                    // WHERE value
                    args.add(resolveValue(action, idMapping, ingestTimestamp));
                    return args.toArray();
                })
                .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
        log.info("Successfully updated {} records in {}", data.size(), tableName);
    }

    @Override
    public Map<String, String> lookupValues(String tableName, String schema, String keyColumn,
                                            String valueColumn, String timestampColumn) {
        String fullTable = schema != null ? schema + "." + tableName : tableName;
        String sql = timestampColumn != null
                ? "SELECT " + keyColumn + ", " + valueColumn + " FROM " + fullTable
                  + " WHERE " + timestampColumn + " = (SELECT MAX(" + timestampColumn + ") FROM " + fullTable + ")"
                : "SELECT " + keyColumn + ", " + valueColumn + " FROM " + fullTable;

        Map<String, String> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String key = rs.getString(1);
            String value = rs.getString(2);
            if (key != null) result.put(key.toLowerCase(), value);
        });
        log.info("Loaded {} lookup entries from {}.{} → {}", result.size(), fullTable, keyColumn, valueColumn);
        return result;
    }

    private Object resolveConcatenated(Action action, DatabaseMapping mapping) {
        return mapping.concatenate().stream()
                .map(col -> {
                    Object val = action.get(col);
                    return val != null ? val.toString() : "";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(mapping.separator()));
    }
}
