package com.xuan.boot.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Spring Boot 4 no longer discovers Flyway from the old monolithic auto-configuration jar.
 * Keep migration ownership explicit while this project depends on flyway-core directly.
 */
@Configuration
public class FlywayMigrationConfig {

    @Bean(initMethod = "migrate")
    public Flyway classroomFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }
}
