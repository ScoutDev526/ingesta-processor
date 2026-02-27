package es.ing.icenterprise.arthur.core.ports.outbound;

import java.util.List;

/**
 * Port to introspect database table metadata.
 * Used by the auto-mapper to discover target table columns.
 */
public interface TableMetadataPort {

    /**
     * Returns the list of column names for the given table.
     *
     * @param tableName table name
     * @param schema    schema name (nullable, uses default if null)
     * @return list of column names in UPPER_CASE
     */
    List<String> getColumnNames(String tableName, String schema);
}
