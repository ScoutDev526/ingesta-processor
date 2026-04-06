package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import es.ing.icenterprise.arthur.core.ports.outbound.DepartmentQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class JdbcDepartmentQueryAdapter implements DepartmentQueryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcDepartmentQueryAdapter.class);
    private static final int MAX_TREE_DEPTH = 50;

    private static final List<String> DOMAIN_LEAD_ROLES = List.of(
            "COO_DomainLead", "RB_DomainLead", "HR_DomainLead", "WB_DomainLead",
            "CFO_DomainLead", "CRO_DomainLead", "CIO_DomainLead");

    private final JdbcTemplate jdbcTemplate;

    public JdbcDepartmentQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, String> getDomainLeadCkToRoleMap(LocalDate timestamp) {
        Timestamp ts = Timestamp.valueOf(timestamp.atStartOfDay());
        String placeholders = DOMAIN_LEAD_ROLES.stream().map(r -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT ra.CK, r.name " +
                     "FROM ROLES_ASSIGNATION ra " +
                     "JOIN ROLES r ON ra.ID = r.id " +
                     "JOIN hr h ON ra.CK = h.id " +
                     "WHERE h.timestamp = ? " +
                     "AND r.name IN (" + placeholders + ")";

        Object[] params = new Object[1 + DOMAIN_LEAD_ROLES.size()];
        params[0] = ts;
        for (int i = 0; i < DOMAIN_LEAD_ROLES.size(); i++) {
            params[1 + i] = DOMAIN_LEAD_ROLES.get(i);
        }

        Map<String, String> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("CK"), rs.getString("name"));
        }, params);

        log.info("Loaded {} domain lead CKs for timestamp {}", result.size(), timestamp);
        return result;
    }

    @Override
    public List<String[]> getNewDepartments(LocalDate timestamp) {
        Timestamp ts = Timestamp.valueOf(timestamp.atStartOfDay());
        String sql = "SELECT DISTINCT h.id, h.department " +
                     "FROM hr h " +
                     "WHERE h.timestamp = ? " +
                     "AND h.department IS NOT NULL " +
                     "AND h.department NOT IN (SELECT ID FROM DEPARTMENT2)";

        List<String[]> result = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            result.add(new String[]{rs.getString("id"), rs.getString("department")});
        }, ts);

        log.info("Found {} employees with new departments at timestamp {}", result.size(), timestamp);
        return result;
    }

    @Override
    public List<String> getManagerTreeUp(String ck, LocalDate timestamp) {
        Timestamp ts = Timestamp.valueOf(timestamp.atStartOfDay());
        List<String> tree = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        String current = ck;

        while (current != null && visited.add(current) && tree.size() < MAX_TREE_DEPTH) {
            tree.add(current);
            List<String> managers = jdbcTemplate.queryForList(
                    "SELECT ckmanager FROM hr WHERE id = ? AND timestamp = ?",
                    String.class, current, ts);
            if (managers.isEmpty() || managers.get(0) == null || managers.get(0).isBlank()) break;
            current = managers.get(0);
        }

        return tree;
    }

    @Override
    public void insertDepartment(String departmentId, String departmentType, String domain) {
        jdbcTemplate.update(
                "INSERT INTO DEPARTMENT2 (ID, DEPARTMENT_TYPE, DOMAIN) VALUES (?, ?, ?)",
                departmentId, departmentType, domain);
    }
}
