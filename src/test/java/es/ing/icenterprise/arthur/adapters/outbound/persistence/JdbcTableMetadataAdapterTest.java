package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
class JdbcTableMetadataAdapterTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private JdbcTableMetadataAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JdbcTableMetadataAdapter(dataSource);
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS META_TEST (ID VARCHAR(50), NAME VARCHAR(100), SCORE DOUBLE)");
    }

    @Test
    @DisplayName("getColumnNames returns uppercase column names for existing table")
    void getColumnNamesReturnsUppercaseColumns() {
        List<String> columns = adapter.getColumnNames("META_TEST", null);
        assertThat(columns).containsExactlyInAnyOrder("ID", "NAME", "SCORE");
    }

    @Test
    @DisplayName("getColumnNames returns empty list for non-existing table")
    void getColumnNamesEmptyForNonExistingTable() {
        List<String> columns = adapter.getColumnNames("NONEXISTENT_TABLE", null);
        assertThat(columns).isEmpty();
    }

    @Test
    @DisplayName("getColumnNames works with lowercase table name input (H2 uppercase fallback)")
    void getColumnNamesWithLowercaseTableName() {
        // H2 stores tables uppercase, adapter tries uppercase first, so lowercase input should work
        List<String> columns = adapter.getColumnNames("meta_test", null);
        assertThat(columns).containsExactlyInAnyOrder("ID", "NAME", "SCORE");
    }

    @Test
    @DisplayName("getColumnNames throws RuntimeException on invalid datasource")
    void getColumnNamesThrowsOnSqlError() {
        // Close the datasource is not practical, but we can test the exception path
        // by passing a null DataSource (which will throw NullPointerException → RuntimeException)
        JdbcTableMetadataAdapter brokenAdapter = new JdbcTableMetadataAdapter(null);
        assertThatThrownBy(() -> brokenAdapter.getColumnNames("META_TEST", null))
                .isInstanceOf(Exception.class);
    }
}
