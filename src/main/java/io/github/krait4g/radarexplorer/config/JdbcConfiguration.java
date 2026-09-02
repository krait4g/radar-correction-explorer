package io.github.krait4g.radarexplorer.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class JdbcConfiguration {

    @Bean
    DataSource radarDataSource(RadarDatabaseProperties database) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("radar-explorer-read-only");
        dataSource.setJdbcUrl(database.getJdbcUrl());
        dataSource.setUsername(database.getUsername());
        dataSource.setPassword(database.getPassword());
        // The in-memory demo is seeded once at startup. External datasources remain read-only at
        // both the JDBC connection level and, as documented, through a SELECT-only DB role.
        dataSource.setReadOnly(!database.isDemo());
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(3_000);
        dataSource.setValidationTimeout(1_000);
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    @Bean
    JdbcTemplate radarJdbcTemplate(DataSource radarDataSource, ViewerProperties properties) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(radarDataSource);
        jdbcTemplate.setQueryTimeout(properties.getLimits().getStatementTimeoutSeconds());
        return jdbcTemplate;
    }
}
