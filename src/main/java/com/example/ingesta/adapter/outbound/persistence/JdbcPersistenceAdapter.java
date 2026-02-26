package com.example.ingesta.adapter.outbound.persistence;

import com.example.ingesta.core.domain.model.Action;
import com.example.ingesta.core.port.outbound.PersistencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Component
public class JdbcPersistenceAdapter implements PersistencePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcPersistenceAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public JdbcPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertData(List<Action> data, Map<String, Object> parameters) {
        String tableName = (String) parameters.getOrDefault("tableName", "ingesta_data");
        log.info("Inserting {} records into table: {}", data.size(), tableName);

        if (data.isEmpty()) return;

        // Build insert SQL from first row's keys
        Action first = data.get(0);
        List<String> columns = first.data().keySet().stream().toList();

        StringJoiner columnJoiner = new StringJoiner(", ");
        StringJoiner placeholderJoiner = new StringJoiner(", ");
        columns.forEach(col -> {
            columnJoiner.add(col);
            placeholderJoiner.add("?");
        });

        String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                tableName, columnJoiner, placeholderJoiner);

        List<Object[]> batchArgs = data.stream()
                .map(action -> columns.stream()
                        .map(col -> action.get(col))
                        .toArray())
                .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
        log.info("Successfully inserted {} records", data.size());
    }

    @Override
    public Object check(Object data, Map<String, Object> parameters) {
        String query = (String) parameters.getOrDefault("query", "SELECT 1");
        log.info("Executing check query: {}", query);
        return jdbcTemplate.queryForObject(query, Object.class);
    }

    @Override
    public void truncate(Map<String, Object> parameters) {
        String tableName = (String) parameters.getOrDefault("tableName", "ingesta_data");
        log.info("Truncating table: {}", tableName);
        jdbcTemplate.execute("TRUNCATE TABLE " + tableName);
    }
}
