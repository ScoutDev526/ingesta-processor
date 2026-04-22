package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Deletes HR rows for a given day before the LDAP ingestion reinserts.
 *
 * <p>Uses a direct {@code timestamp} equality check (JDBC-level type conversion) rather than an
 * Oracle-specific {@code TO_TIMESTAMP(string, format, nls)} call, so the same SQL works on
 * Oracle (production) and H2 (tests).
 */
@Mapper
public interface ImportDataHrDeleteMapper {

    /**
     * Delete HR rows whose {@code timestamp} matches the given value.
     *
     * @param timestamp start-of-day of the ingestion date
     * @return number of deleted rows
     */
    @Delete("DELETE FROM hr WHERE timestamp = #{timestamp,jdbcType=TIMESTAMP}")
    int deleteByTimestamp(@Param("timestamp") LocalDateTime timestamp);
}
