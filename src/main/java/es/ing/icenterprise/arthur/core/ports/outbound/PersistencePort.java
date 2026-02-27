package es.ing.icenterprise.arthur.core.ports.outbound;

import es.ing.icenterprise.arthur.core.domain.model.Action;
import es.ing.icenterprise.arthur.core.domain.model.DatabaseMapping;

import java.util.List;
import java.util.Map;

/**
 * Port for inserting/checking data against the target database.
 */
public interface PersistencePort {

    /**
     * Inserts data using resolved column mappings.
     * Only mapped columns are inserted; Excel columns without mapping are ignored.
     *
     * @param data       list of actions (rows from Excel)
     * @param mappings   resolved column mappings (from ColumnAutoMapper)
     * @param parameters additional parameters (tableName, schema, etc.)
     */
    void insertData(List<Action> data, List<DatabaseMapping> mappings, Map<String, Object> parameters);

    Object check(Object data, Map<String, Object> parameters);

    void truncate(Map<String, Object> parameters);
}
