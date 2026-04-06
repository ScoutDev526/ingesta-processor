package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import es.ing.icenterprise.arthur.core.ports.outbound.RoleQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

@Component
public class JdbcRoleQueryAdapter implements RoleQueryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRoleQueryAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public JdbcRoleQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Integer> loadRoleNameToIdMapping() {
        String sql = "SELECT name, id FROM roles";
        Map<String, Integer> mapping = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            mapping.put(rs.getString("name"), rs.getInt("id"));
        });
        log.info("Loaded {} role definitions from ROLES table", mapping.size());
        return mapping;
    }

    @Override
    public Set<String> executeRoleQuery(String sql, LocalDate timestamp, int paramCount) {
        Timestamp ts = Timestamp.valueOf(timestamp.atStartOfDay());
        Object[] params = new Object[paramCount];
        Arrays.fill(params, ts);

        Set<String> owners = new LinkedHashSet<>();
        jdbcTemplate.query(sql, rs -> {
            String ownerId = rs.getString(1);
            if (ownerId != null && !ownerId.isBlank()) {
                owners.add(ownerId);
            }
        }, params);
        return owners;
    }

    @Override
    public void truncateOwnerRoles(LocalDate timestamp) {
        Timestamp ts = Timestamp.valueOf(timestamp.atStartOfDay());
        int deleted = jdbcTemplate.update("DELETE FROM OWNER_ROLE WHERE timestamp = ?", ts);
        log.info("Deleted {} records from OWNER_ROLE for timestamp {}", deleted, timestamp);
    }

    @Override
    public void batchInsertOwnerRoles(LocalDate timestamp, int roleId, Set<String> ownerIds) {
        if (ownerIds.isEmpty()) return;

        String sql = "INSERT INTO OWNER_ROLE (timestamp, owner_id, role_id) VALUES (?, ?, ?)";
        Timestamp ts = Timestamp.valueOf(timestamp.atStartOfDay());

        List<Object[]> batchArgs = ownerIds.stream()
                .map(ownerId -> new Object[]{ts, ownerId, roleId})
                .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
        log.debug("Inserted {} owner-role records for roleId={}", ownerIds.size(), roleId);
    }
}
