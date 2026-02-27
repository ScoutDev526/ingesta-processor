package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.domain.model.DatabaseMapping;
import es.ing.icenterprise.arthur.core.ports.outbound.TableMetadataPort;
import es.ing.icenterprise.arthur.core.utils.ColumnNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Auto-maps Excel headers to database columns using UPPER_SNAKE_CASE normalization.
 *
 * Flow:
 *   1. Read DB table column names via TableMetadataPort
 *   2. Normalize Excel headers with ColumnNormalizer
 *   3. Match: normalized Excel header == DB column name
 *   4. Merge with explicit mappings from YAML (autoGenerate, concatenate, overrides)
 *   5. Return final list of DatabaseMapping
 *
 * Columns that don't match are ignored (logged as warning).
 * DB columns without a match and without explicit mapping are also logged.
 */
@Component
public class ColumnAutoMapper {

    private static final Logger log = LoggerFactory.getLogger(ColumnAutoMapper.class);

    private final TableMetadataPort tableMetadataPort;

    public ColumnAutoMapper(TableMetadataPort tableMetadataPort) {
        this.tableMetadataPort = tableMetadataPort;
    }

    /**
     * Builds the full list of DatabaseMapping by auto-mapping Excel headers to DB columns,
     * then merging with any explicit mappings from the YAML.
     *
     * @param excelHeaders     headers from the Excel file (original names)
     * @param tableName        target table name
     * @param schema           target schema (nullable)
     * @param explicitMappings explicit mappings from YAML (autoGenerate, concatenate, overrides)
     * @return merged list of DatabaseMapping ready for INSERT
     */
    public List<DatabaseMapping> resolve(List<String> excelHeaders,
                                         String tableName,
                                         String schema,
                                         List<DatabaseMapping> explicitMappings) {

        // 1. Get DB columns
        List<String> dbColumns = tableMetadataPort.getColumnNames(tableName, schema);
        Set<String> dbColumnSet = new LinkedHashSet<>(dbColumns);

        // 2. Collect DB columns already covered by explicit mappings
        Set<String> explicitlyMappedDbColumns = explicitMappings.stream()
                .map(DatabaseMapping::dbColumn)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        // 3. Auto-map: normalize each Excel header → match against DB columns
        List<DatabaseMapping> autoMapped = new ArrayList<>();
        List<String> unmatchedHeaders = new ArrayList<>();

        for (String header : excelHeaders) {
            String normalized = ColumnNormalizer.normalize(header);
            if (normalized == null) continue;

            if (dbColumnSet.contains(normalized) && !explicitlyMappedDbColumns.contains(normalized)) {
                autoMapped.add(new DatabaseMapping(header, normalized));
                log.debug("Auto-mapped: '{}' → {}", header, normalized);
            } else if (!dbColumnSet.contains(normalized)) {
                unmatchedHeaders.add(header);
            }
            // If it's in explicitlyMapped, the explicit mapping takes priority → skip auto
        }

        if (!unmatchedHeaders.isEmpty()) {
            log.warn("Excel columns without DB match (ignored): {}", unmatchedHeaders);
        }

        // 4. Check for DB columns not covered by any mapping
        Set<String> allMappedDbColumns = new HashSet<>(explicitlyMappedDbColumns);
        autoMapped.forEach(m -> allMappedDbColumns.add(m.dbColumn()));

        List<String> unmappedDbColumns = dbColumns.stream()
                .filter(col -> !allMappedDbColumns.contains(col))
                .toList();

        if (!unmappedDbColumns.isEmpty()) {
            log.warn("DB columns without mapping (will be NULL or default): {}", unmappedDbColumns);
        }

        // 5. Merge: auto-mapped first, then explicit
        List<DatabaseMapping> result = new ArrayList<>(autoMapped);
        result.addAll(explicitMappings);

        log.info("Resolved {} mappings ({} auto + {} explicit) for table {}.{}",
                result.size(), autoMapped.size(), explicitMappings.size(), schema, tableName);

        return result;
    }
}
