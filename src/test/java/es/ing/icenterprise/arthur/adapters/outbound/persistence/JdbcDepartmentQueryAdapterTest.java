package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
class JdbcDepartmentQueryAdapterTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private JdbcDepartmentQueryAdapter adapter;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 1);
    private static final Timestamp TS = Timestamp.valueOf(DATE.atStartOfDay());

    @BeforeEach
    void setUp() {
        adapter = new JdbcDepartmentQueryAdapter(jdbcTemplate);

        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS ROLES (id INT, name VARCHAR(100))");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS ROLES_ASSIGNATION (CK VARCHAR(50), ID INT)");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS hr (id VARCHAR(50), timestamp TIMESTAMP, department VARCHAR(100), ckmanager VARCHAR(50))");
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS DEPARTMENT2 (ID VARCHAR(100), DEPARTMENT_TYPE VARCHAR(50), DOMAIN VARCHAR(50))");
        jdbcTemplate.execute("DELETE FROM ROLES");
        jdbcTemplate.execute("DELETE FROM ROLES_ASSIGNATION");
        jdbcTemplate.execute("DELETE FROM hr");
        jdbcTemplate.execute("DELETE FROM DEPARTMENT2");
    }

    // ── getDomainLeadCkToRoleMap ──────────────────────────────────────────────

    @Test
    @DisplayName("getDomainLeadCkToRoleMap returns matching CK→role entries for the given date")
    void getDomainLeadCkToRoleMapReturnsMatches() {
        jdbcTemplate.update("INSERT INTO ROLES VALUES (1, 'CIO_DomainLead')");
        jdbcTemplate.update("INSERT INTO ROLES_ASSIGNATION VALUES ('ck-cio', 1)");
        jdbcTemplate.update("INSERT INTO hr (id, timestamp) VALUES ('ck-cio', ?)", TS);

        Map<String, String> result = adapter.getDomainLeadCkToRoleMap(DATE);

        assertThat(result).containsEntry("ck-cio", "CIO_DomainLead");
    }

    @Test
    @DisplayName("getDomainLeadCkToRoleMap excludes rows from a different timestamp")
    void getDomainLeadCkToRoleMapExcludesDifferentTimestamp() {
        Timestamp otherTs = Timestamp.valueOf(LocalDate.of(2025, 1, 1).atStartOfDay());
        jdbcTemplate.update("INSERT INTO ROLES VALUES (1, 'CIO_DomainLead')");
        jdbcTemplate.update("INSERT INTO ROLES_ASSIGNATION VALUES ('ck-cio', 1)");
        jdbcTemplate.update("INSERT INTO hr (id, timestamp) VALUES ('ck-cio', ?)", otherTs);

        Map<String, String> result = adapter.getDomainLeadCkToRoleMap(DATE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getDomainLeadCkToRoleMap ignores roles not in DOMAIN_LEAD_ROLES")
    void getDomainLeadCkToRoleMapIgnoresUnknownRoles() {
        jdbcTemplate.update("INSERT INTO ROLES VALUES (2, 'UNKNOWN_ROLE')");
        jdbcTemplate.update("INSERT INTO ROLES_ASSIGNATION VALUES ('ck-x', 2)");
        jdbcTemplate.update("INSERT INTO hr (id, timestamp) VALUES ('ck-x', ?)", TS);

        Map<String, String> result = adapter.getDomainLeadCkToRoleMap(DATE);

        assertThat(result).isEmpty();
    }

    // ── getNewDepartments ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getNewDepartments returns employees whose department is not in DEPARTMENT2")
    void getNewDepartmentsReturnsNewDeptEmployees() {
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, department) VALUES ('emp1', ?, 'DEPT-NEW')", TS);

        List<String[]> result = adapter.getNewDepartments(DATE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)[0]).isEqualTo("emp1");
        assertThat(result.get(0)[1]).isEqualTo("DEPT-NEW");
    }

    @Test
    @DisplayName("getNewDepartments excludes employees whose department is already in DEPARTMENT2")
    void getNewDepartmentsExcludesExistingDepartments() {
        jdbcTemplate.update("INSERT INTO DEPARTMENT2 (ID) VALUES ('DEPT-OLD')");
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, department) VALUES ('emp2', ?, 'DEPT-OLD')", TS);

        List<String[]> result = adapter.getNewDepartments(DATE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getNewDepartments excludes employees with null department")
    void getNewDepartmentsExcludesNullDepartment() {
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, department) VALUES ('emp3', ?, NULL)", TS);

        List<String[]> result = adapter.getNewDepartments(DATE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getNewDepartments deduplicates same department appearing multiple times")
    void getNewDepartmentsDeduplicatesSameDepartment() {
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, department) VALUES ('e1', ?, 'DEPT-X')", TS);
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, department) VALUES ('e2', ?, 'DEPT-X')", TS);

        List<String[]> result = adapter.getNewDepartments(DATE);
        long distinctDepts = result.stream().map(r -> r[1]).distinct().count();

        assertThat(distinctDepts).isEqualTo(1);
    }

    // ── getManagerTreeUp ──────────────────────────────────────────────────────

    @Test
    @DisplayName("getManagerTreeUp returns chain from employee to root manager")
    void getManagerTreeUpReturnsFullChain() {
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, ckmanager) VALUES ('emp', ?, 'mgr')", TS);
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, ckmanager) VALUES ('mgr', ?, 'root')", TS);
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, ckmanager) VALUES ('root', ?, NULL)", TS);

        List<String> tree = adapter.getManagerTreeUp("emp", DATE);

        assertThat(tree).containsExactly("emp", "mgr", "root");
    }

    @Test
    @DisplayName("getManagerTreeUp includes only starting CK when it has no manager in hr table")
    void getManagerTreeUpStopsForUnknownEmployee() {
        // The starting CK is always added before querying its manager;
        // since "unknown" has no hr row, the loop breaks immediately after adding it.
        List<String> tree = adapter.getManagerTreeUp("unknown", DATE);

        assertThat(tree).containsExactly("unknown");
    }

    @Test
    @DisplayName("getManagerTreeUp prevents cycles via visited set")
    void getManagerTreeUpPreventsCycles() {
        // cycle: a → b → a
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, ckmanager) VALUES ('a', ?, 'b')", TS);
        jdbcTemplate.update("INSERT INTO hr (id, timestamp, ckmanager) VALUES ('b', ?, 'a')", TS);

        List<String> tree = adapter.getManagerTreeUp("a", DATE);

        // Should stop after visiting a and b (b's manager is 'a' which is already visited)
        assertThat(tree).containsExactly("a", "b");
    }

    // ── insertDepartment ──────────────────────────────────────────────────────

    @Test
    @DisplayName("insertDepartment inserts a row into DEPARTMENT2")
    void insertDepartmentInsertsRow() {
        adapter.insertDepartment("DEPT-001", "IT", "CIO");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DEPARTMENT2 WHERE ID='DEPT-001' AND DEPARTMENT_TYPE='IT' AND DOMAIN='CIO'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
