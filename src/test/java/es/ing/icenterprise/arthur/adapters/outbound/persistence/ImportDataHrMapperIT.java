package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import es.ing.icenterprise.arthur.core.domain.model.ImportDataHrPerson;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@MybatisTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:hrtest;MODE=Oracle;DATABASE_TO_LOWER=true;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password="
        })
class ImportDataHrMapperIT {

    @Autowired ImportDataHrMapper insertMapper;
    @Autowired ImportDataHrDeleteMapper deleteMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS hr");
        jdbcTemplate.execute(
                "CREATE TABLE hr ("
                        + "id VARCHAR(50), "
                        + "name VARCHAR(200), "
                        + "mail VARCHAR(200), "
                        + "department VARCHAR(200), "
                        + "ckmanager VARCHAR(50), "
                        + "title VARCHAR(100), "
                        + "timestamp TIMESTAMP)");
    }

    @Test
    @DisplayName("insertOne inserts a row into the hr table")
    void insertOneInsertsRow() {
        LocalDateTime ts = LocalDateTime.of(2026, 4, 14, 0, 0);
        ImportDataHrPerson p =
                new ImportDataHrPerson(
                        "XT5ORI", "Maria Fuentes", "m@i.com", "HR", "NT25YI", "CFO", ts);

        int inserted = insertMapper.insertOne(p);

        assertThat(inserted).isEqualTo(1);

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM hr WHERE id = ?", Integer.class, "XT5ORI");
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteByDayString deletes rows matching the given day in Oracle format")
    void deleteByDayStringDeletesMatchingRows() {
        LocalDateTime ts = LocalDateTime.of(2026, 4, 14, 0, 0);
        insertMapper.insertOne(
                new ImportDataHrPerson("A1", "A", "a@i.com", "HR", "NT1", "T", ts));
        insertMapper.insertOne(
                new ImportDataHrPerson("B2", "B", "b@i.com", "HR", "NT2", "T", ts));

        int deleted = deleteMapper.deleteByDayString("14-APR-26");

        assertThat(deleted).isEqualTo(2);
        Integer remaining = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hr", Integer.class);
        assertThat(remaining).isEqualTo(0);
    }
}
