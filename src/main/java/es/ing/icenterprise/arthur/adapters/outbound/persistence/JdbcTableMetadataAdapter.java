package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import es.ing.icenterprise.arthur.core.ports.outbound.TableMetadataPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Component
public class JdbcTableMetadataAdapter implements TableMetadataPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcTableMetadataAdapter.class);

    private final DataSource dataSource;

    public JdbcTableMetadataAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<String> getColumnNames(String tableName, String schema) {
        log.info("Reading metadata for table: {}.{}", schema, tableName);

        List<String> columns = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            // Try uppercase first (Oracle convention), then as-is
            try (ResultSet rs = meta.getColumns(null,
                    schema != null ? schema.toUpperCase() : null,
                    tableName.toUpperCase(),
                    null)) {

                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toUpperCase());
                }
            }

            // Fallback: try without uppercasing (PostgreSQL, H2 mixed case)
            if (columns.isEmpty()) {
                try (ResultSet rs = meta.getColumns(null, schema, tableName, null)) {
                    while (rs.next()) {
                        columns.add(rs.getString("COLUMN_NAME").toUpperCase());
                    }
                }
            }

            log.info("Found {} columns in {}: {}", columns.size(), tableName, columns);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to read table metadata for: " + tableName, e);
        }

        return columns;
    }
}
