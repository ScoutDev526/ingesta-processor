package es.ing.icenterprise.arthur.core.ports.outbound;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Port for role ownership database operations.
 * Used by RoleOwnershipService to compute which users (CKs) own which roles.
 */
public interface RoleQueryPort {

    /**
     * Loads the mapping of role name → role ID from the ROLES table.
     */
    Map<String, Integer> loadRoleNameToIdMapping();

    /**
     * Executes a role query and returns the distinct set of owner identifiers (first column).
     *
     * @param sql       SQL query with {@code ?} placeholders for the timestamp
     * @param timestamp the ingest date used as parameter
     * @param paramCount number of {@code ?} placeholders in the query
     * @return set of unique owner identifiers
     */
    Set<String> executeRoleQuery(String sql, LocalDate timestamp, int paramCount);

    /**
     * Deletes all OWNER_ROLE records for the given timestamp.
     */
    void truncateOwnerRoles(LocalDate timestamp);

    /**
     * Batch-inserts owner-role associations.
     */
    void batchInsertOwnerRoles(LocalDate timestamp, int roleId, Set<String> ownerIds);
}
