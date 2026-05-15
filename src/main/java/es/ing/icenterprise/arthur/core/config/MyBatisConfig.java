package es.ing.icenterprise.arthur.core.config;

import javax.sql.DataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers MyBatis mappers only when a DataSource is present.
 *
 * <p>The {@link ConditionalOnBean} guard keeps slice tests (e.g. {@code @WebMvcTest}) working: they
 * exclude DataSource autoconfiguration, so the mappers are not scanned and MyBatis does not try to
 * build a {@code SqlSessionFactory} it cannot satisfy.
 */
@Configuration
@ConditionalOnBean(DataSource.class)
@MapperScan("es.ing.icenterprise.arthur.adapters.outbound.persistence")
public class MyBatisConfig {}
