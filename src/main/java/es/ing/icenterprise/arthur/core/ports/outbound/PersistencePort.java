package es.ing.icenterprise.arthur.core.ports.outbound;

import es.ing.icenterprise.arthur.core.domain.model.Action;
import es.ing.icenterprise.arthur.core.domain.model.DatabaseMapping;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port for inserting/checking data against the target database.
 */
public interface PersistencePort {

    /**
     * Inserts data using resolved column mappings.
     * Only mapped columns are inserted; Excel columns without mapping are ignored.
     *
     * <p>On a batch failure the implementation is expected to bisect the chunk and
     * retry each half so that rows rejected by the database (constraint violations,
     * bad values, ...) are isolated instead of poisoning the whole batch. The
     * returned {@link InsertResult} reports how many rows were persisted vs. rejected.
     *
     * @param data       list of actions (rows from Excel)
     * @param mappings   resolved column mappings (from ColumnAutoMapper)
     * @param parameters additional parameters (tableName, schema, etc.)
     * @return a summary of rows inserted and rows skipped due to errors
     */
    InsertResult insertData(List<Action> data, List<DatabaseMapping> mappings, Map<String, Object> parameters);

    Object check(Object data, Map<String, Object> parameters);

    void truncate(Map<String, Object> parameters);

    /**
     * Checks whether a record exists in a table matching idColumn = idValue AND timestampColumn = date.
     */
    boolean checkExists(String tableName, String schema, String idColumn, Object idValue,
                        String timestampColumn, LocalDate date);

    /**
     * Inserts a single row with raw column→value pairs. LocalDate values are stored as TIMESTAMP at midnight.
     */
    void insertRow(String tableName, String schema, Map<String, Object> columnValues);

    /**
     * Loads all values of idColumn from a table filtered by timestampColumn = date into a Set.
     * Used as a cache for VALIDATE_REFERENCE steps to avoid N+1 queries.
     */
    Set<Object> loadReferenceIds(String tableName, String schema, String idColumn,
                                 String timestampColumn, LocalDate date);

    /**
     * Loads all values of idColumn from a table into a Set (no timestamp filter).
     * Used for INSERT steps with skipExisting=true to avoid inserting duplicate rows.
     */
    Set<Object> loadExistingIds(String tableName, String schema, String idColumn);

    /**
     * Updates existing rows matched by idColumn. Each row's ID value is used in the WHERE clause.
     * Only mapped columns (excluding the ID column itself) are updated.
     */
    void updateData(List<Action> data, List<DatabaseMapping> mappings, Map<String, Object> parameters, String idColumn);

    /**
     * Loads a full key→value map from a reference table for in-memory lookups.
     * If timestampColumn is provided, only the most recent snapshot is loaded
     * (WHERE timestampColumn = MAX(timestampColumn)). Keys are lowercased for
     * case-insensitive matching.
     *
     * @param tableName       reference table name
     * @param schema          schema prefix (nullable)
     * @param keyColumn       column whose value is the lookup key (e.g. "mail")
     * @param valueColumn     column whose value is the lookup result (e.g. "id")
     * @param timestampColumn column to filter by latest snapshot, or null to skip
     * @return map of lowercased key → value
     */
    Map<String, String> lookupValues(String tableName, String schema, String keyColumn,
                                     String valueColumn, String timestampColumn);
}
