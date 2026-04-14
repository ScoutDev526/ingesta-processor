package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
class JdbcRoleQueryAdapterTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private JdbcRoleQueryAdapter adapter;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 1);
    private static final Timestamp TS = Timestamp.valueOf(DATE.atStartOfDay());

    @BeforeEach
    void setUp() {
        adapter = new JdbcRoleQueryAdapter(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS roles (id INT, name VARCHAR(100))");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS OWNER_ROLE (timestamp TIMESTAMP, owner_id VARCHAR(50), role_id INT)");
        jdbcTemplate.execute("DELETE FROM roles");
        jdbcTemplate.execute("DELETE FROM OWNER_ROLE");
    }

    // ── loadRoleNameToIdMapping ───────────────────────────────────────────────

    @Test
    @DisplayName("loadRoleNameToIdMapping returns all name→id pairs from roles table")
    void loadRoleNameToIdMappingReturnsAllRoles() {
        jdbcTemplate.update("INSERT INTO roles VALUES (1, 'CEO')");
        jdbcTemplate.update("INSERT INTO roles VALUES (2, 'TribeLead')");

        Map<String, Integer> mapping = adapter.loadRoleNameToIdMapping();

        assertThat(mapping).containsEntry("CEO", 1).containsEntry("TribeLead", 2);
    }

    @Test
    @DisplayName("loadRoleNameToIdMapping returns empty map when roles table is empty")
    void loadRoleNameToIdMappingEmptyTable() {
        Map<String, Integer> mapping = adapter.loadRoleNameToIdMapping();
        assertThat(mapping).isEmpty();
    }

    // ── executeRoleQuery ──────────────────────────────────────────────────────

    @Test
    @DisplayName("executeRoleQuery returns matching owner_ids for given timestamp")
    void executeRoleQueryReturnsMatchingOwners() {
        jdbcTemplate.update("INSERT INTO OWNER_ROLE VALUES (?, 'ck-alice', 1)", TS);
        jdbcTemplate.update("INSERT INTO OWNER_ROLE VALUES (?, 'ck-bob', 1)", TS);
        Timestamp otherTs = Timestamp.valueOf(LocalDate.of(2025, 1, 1).atStartOfDay());
        jdbcTemplate.update("INSERT INTO OWNER_ROLE VALUES (?, 'ck-carol', 1)", otherTs);

        String query = "SELECT owner_id FROM OWNER_ROLE WHERE timestamp = ?";
        Set<String> result = adapter.executeRoleQuery(query, DATE, 1);

        assertThat(result).containsExactlyInAnyOrder("ck-alice", "ck-bob");
    }

    @Test
    @DisplayName("executeRoleQuery skips null and blank owner_ids")
    void executeRoleQuerySkipsNullAndBlank() {
        jdbcTemplate.update("INSERT INTO OWNER_ROLE VALUES (?, NULL, 1)", TS);
        jdbcTemplate.update("INSERT INTO OWNER_ROLE VALUES (?, 'ck-valid', 1)", TS);

        String query = "SELECT owner_id FROM OWNER_ROLE WHERE timestamp = ?";
        Set<String> result = adapter.executeRoleQuery(query, DATE, 1);

        assertThat(result).containsExactly("ck-valid");
    }

    // ── truncateOwnerRoles ────────────────────────────────────────────────────

    @Test
    @DisplayName("truncateOwnerRoles deletes rows for the given timestamp only")
    void truncateOwnerRolesDeletesMatchingRows() {
        jdbcTemplate.update("INSERT INTO OWNER_ROLE VALUES (?, 'ck1', 1)", TS);
        Timestamp oldTs = Timestamp.valueOf(LocalDate.of(2025, 1, 1).atStartOfDay());
        jdbcTemplate.update("INSERT INTO OWNER_ROLE VALUES (?, 'ck2', 1)", oldTs);

        adapter.truncateOwnerRoles(DATE);

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM OWNER_ROLE", Integer.class);
        assertThat(count).isEqualTo(1); // only old row remains
        String remaining = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM OWNER_ROLE", String.class);
        assertThat(remaining).isEqualTo("ck2");
    }

    @Test
    @DisplayName("truncateOwnerRoles is a no-op when no rows match the timestamp")
    void truncateOwnerRolesNoOpWhenNoMatch() {
        jdbcTemplate.update("INSERT INTO OWNER_ROLE VALUES (?, 'ck1', 1)",
                Timestamp.valueOf(LocalDate.of(2025, 1, 1).atStartOfDay()));

        adapter.truncateOwnerRoles(DATE); // no rows for this date

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM OWNER_ROLE", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ── batchInsertOwnerRoles ─────────────────────────────────────────────────

    @Test
    @DisplayName("batchInsertOwnerRoles inserts a row for each ownerId")
    void batchInsertOwnerRolesInsertsAllOwners() {
        adapter.batchInsertOwnerRoles(DATE, 42, Set.of("ck-alice", "ck-bob"));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM OWNER_ROLE WHERE role_id=42", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("batchInsertOwnerRoles does nothing for empty ownerIds set")
    void batchInsertOwnerRolesDoesNothingForEmptySet() {
        adapter.batchInsertOwnerRoles(DATE, 42, Set.of());

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM OWNER_ROLE", Integer.class);
        assertThat(count).isZero();
    }
}
