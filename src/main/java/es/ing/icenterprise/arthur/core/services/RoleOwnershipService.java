package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.ports.outbound.RoleQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Calculates role ownership by querying existing ingested tables (HR, RISK, CONTROL, etc.)
 * and populates the OWNER_ROLE junction table.
 * <p>
 * Migrated from UpdateRolesOwnership.py + Roles.calculateCKRolesList()
 */
@Service
public class RoleOwnershipService {

    private static final Logger log = LoggerFactory.getLogger(RoleOwnershipService.class);

    private final RoleQueryPort roleQueryPort;

    /**
     * Each entry: role name → { sql, paramCount }.
     * The SQL uses {@code ?} as placeholder for the ingest timestamp.
     * Only the first column (owner identifier / CK) is collected from the result.
     */
    private static final Map<String, RoleQuery> ROLE_QUERIES = new LinkedHashMap<>();

    static {
        // HR-based roles (simple id lookup)
        ROLE_QUERIES.put("CEO",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND lower(title) = 'country manager v'", 1));
        ROLE_QUERIES.put("Domain owner",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND ckmanager = ("
                        + "SELECT id FROM hr WHERE timestamp = ? AND lower(title) = 'country manager v')", 2));
        ROLE_QUERIES.put("TribeLead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND lower(title) LIKE '%tribe lead%'", 1));
        ROLE_QUERIES.put("Head/Lead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND (lower(title) LIKE '%lead%' OR "
                        + "lower(title) LIKE '%head%') AND NOT (lower(title) LIKE '%tribe lead%')", 1));

        // Risk roles
        ROLE_QUERIES.put("Risk owner",
                new RoleQuery("SELECT risk_owner, (name || ' (' || REGEXP_SUBSTR(id, '[^/]+$') || ')') AS LABEL, identifier_url "
                        + "FROM risk WHERE timestamp = ? AND (risk.applicability != 'NOT APPLICABLE' OR risk.applicability IS NULL) "
                        + "AND (risk_owner IS NOT NULL) ORDER BY risk_owner, to_char(LABEL)", 1));
        ROLE_QUERIES.put("Risk owner delegate",
                new RoleQuery("SELECT delegate_risk_owner, (name || ' (' || REGEXP_SUBSTR(id, '[^/]+$') || ')') AS LABEL, "
                        + "identifier_url FROM risk WHERE timestamp = ? AND (risk.applicability != 'NOT APPLICABLE' "
                        + "OR risk.applicability IS NULL) AND (delegate_risk_owner IS NOT NULL) ORDER BY delegate_risk_owner, to_char(LABEL)", 1));

        // Control roles
        ROLE_QUERIES.put("Control owner",
                new RoleQuery("SELECT control_owner, (name || ' (' || REGEXP_SUBSTR(id, '[^/]+$') || ')') AS LABEL, "
                        + "identifier_url FROM control WHERE timestamp = ? AND (control.applicability != 'NOT APPLICABLE' "
                        + "OR control.applicability IS NULL) AND (control.status != 'INACTIVE' OR control.status IS NULL) "
                        + "AND (control_owner IS NOT NULL) ORDER BY control_owner, to_char(LABEL)", 1));
        ROLE_QUERIES.put("Control owner delegate",
                new RoleQuery("SELECT delegate_control_owner, (name || ' (' || REGEXP_SUBSTR(id, '[^/]+$') || ')') "
                        + "AS LABEL, identifier_url FROM control WHERE timestamp = ? AND "
                        + "((control.applicability != 'NOT APPLICABLE' OR control.applicability IS NULL) "
                        + "AND (control.status != 'INACTIVE' OR control.status IS NULL)) AND (delegate_control_owner IS NOT NULL) "
                        + "ORDER BY delegate_control_owner, to_char(LABEL)", 1));

        // Issue roles
        ROLE_QUERIES.put("Issue owner",
                new RoleQuery("SELECT issue_owner, (name || ' (' || REGEXP_SUBSTR(id, '[^/]+$') || ')') AS "
                        + "LABEL, identifier_url FROM issues WHERE timestamp = ? AND issues.status = 'OPEN' "
                        + "AND (issue_owner IS NOT NULL) ORDER BY issue_owner, to_char(LABEL)", 1));
        ROLE_QUERIES.put("Issue owner delegate",
                new RoleQuery("SELECT delegate_issue_owner, (name || ' (' || REGEXP_SUBSTR(id, '[^/]+$') || ')') "
                        + "AS LABEL, identifier_url FROM issues WHERE timestamp = ? AND issues.status = 'OPEN' "
                        + "AND (delegate_issue_owner IS NOT NULL) ORDER BY delegate_issue_owner, to_char(LABEL)", 1));

        // Action roles
        ROLE_QUERIES.put("Action owner",
                new RoleQuery("SELECT action_owner, (name || ' (' || REGEXP_SUBSTR(id, '[^/]+$') || ')') AS LABEL, identifier_url "
                        + "FROM action WHERE timestamp = ? AND status != 'CLOSED' AND (action_owner IS NOT NULL) "
                        + "ORDER BY action_owner, to_char(LABEL)", 1));
        ROLE_QUERIES.put("Action owner delegate",
                new RoleQuery("SELECT delegate_action_owner, (name || ' (' || REGEXP_SUBSTR(id, '[^/]+$') || ')') "
                        + "AS LABEL, identifier_url FROM action WHERE timestamp = ? AND status != 'CLOSED' "
                        + "AND (delegate_action_owner IS NOT NULL) ORDER BY delegate_action_owner, to_char(LABEL)", 1));

        // Process / Asset / Event roles
        ROLE_QUERIES.put("Process leader",
                new RoleQuery("SELECT OWNER_CK, NAME, null FROM arisprocess WHERE timestamp = ? AND OWNER_CK IS NOT NULL ORDER BY NAME", 1));
        ROLE_QUERIES.put("Asset owner",
                new RoleQuery("SELECT hr.id, cmdb.id, null FROM cmdb JOIN hr ON lower(cmdb.asset_owner) = lower(hr.name) "
                        + "AND cmdb.timestamp = hr.timestamp WHERE cmdb.timestamp = ? ORDER BY hr.id, cmdb.id", 1));
        ROLE_QUERIES.put("Event owner",
                new RoleQuery("SELECT assigned_to, (name || ' (' || REGEXP_SUBSTR(id, '[^/]+$') || ')') AS LABEL, "
                        + "identifier_url FROM events WHERE timestamp = ? AND status = 'CASE OPEN' AND (assigned_to IS NOT NULL) "
                        + "ORDER BY assigned_to, to_char(LABEL)", 1));

        // CoE / Lead roles (from ROLES_ASSIGNATION)
        ROLE_QUERIES.put("CoE IT Risk Officer",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=13)", 1));
        ROLE_QUERIES.put("BCO Lead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=23)", 1));
        ROLE_QUERIES.put("BCOSPOC",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND ckmanager = (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=23)", 1));
        ROLE_QUERIES.put("OCR CoE Lead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=24)", 1));

        // Domain leads
        ROLE_QUERIES.put("COO_DomainLead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=26)", 1));
        ROLE_QUERIES.put("RB_DomainLead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=27)", 1));
        ROLE_QUERIES.put("HR_DomainLead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=28)", 1));
        ROLE_QUERIES.put("WB_DomainLead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=29)", 1));
        ROLE_QUERIES.put("CFO_DomainLead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=30)", 1));
        ROLE_QUERIES.put("CRO_DomainLead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=31)", 1));
        ROLE_QUERIES.put("CIO_DomainLead",
                new RoleQuery("SELECT id, null, null FROM hr WHERE timestamp = ? AND hr.id IN (SELECT CK FROM ROLES_ASSIGNATION WHERE ID=32)", 1));

        // Business Contract owner (no timestamp filter)
        ROLE_QUERIES.put("Business Contract owner",
                new RoleQuery("SELECT OWNER, (id || ' ' || NAME) AS label, null FROM CONTRACTS WHERE OWNER IS NOT NULL ORDER BY OWNER, label", 0));
    }

    public RoleOwnershipService(RoleQueryPort roleQueryPort) {
        this.roleQueryPort = roleQueryPort;
    }

    /**
     * Calculates and persists role ownership for the given date.
     *
     * @param timestamp the ingest date (typically passed as YYYY-MM-DD)
     * @return summary of the operation
     */
    public RoleOwnershipResult execute(LocalDate timestamp) {
        log.info("Starting role ownership calculation for timestamp: {}", timestamp);

        // 1. Load role name → ID mapping from ROLES table
        Map<String, Integer> roleIds = roleQueryPort.loadRoleNameToIdMapping();

        // 2. Calculate CK lists for each role
        Map<Integer, Set<String>> roleOwners = new LinkedHashMap<>();
        int totalOwners = 0;

        for (Map.Entry<String, RoleQuery> entry : ROLE_QUERIES.entrySet()) {
            String roleName = entry.getKey();
            RoleQuery roleQuery = entry.getValue();

            Integer roleId = roleIds.get(roleName);
            if (roleId == null) {
                log.warn("Role '{}' not found in ROLES table, skipping", roleName);
                continue;
            }

            try {
                Set<String> owners = roleQueryPort.executeRoleQuery(roleQuery.sql(), timestamp, roleQuery.paramCount());
                roleOwners.put(roleId, owners);
                totalOwners += owners.size();
                log.debug("Role '{}' (id={}): {} owners found", roleName, roleId, owners.size());
            } catch (Exception e) {
                log.error("Failed to execute query for role '{}': {}", roleName, e.getMessage());
            }
        }

        // 3. Truncate OWNER_ROLE for this timestamp
        roleQueryPort.truncateOwnerRoles(timestamp);

        // 4. Insert all owner-role associations
        for (Map.Entry<Integer, Set<String>> entry : roleOwners.entrySet()) {
            roleQueryPort.batchInsertOwnerRoles(timestamp, entry.getKey(), entry.getValue());
        }

        log.info("Role ownership finished: {} roles processed, {} total owner-role records inserted",
                roleOwners.size(), totalOwners);

        return new RoleOwnershipResult(roleOwners.size(), totalOwners);
    }

    public record RoleQuery(String sql, int paramCount) {}

    public record RoleOwnershipResult(int rolesProcessed, int totalRecords) {}
}
