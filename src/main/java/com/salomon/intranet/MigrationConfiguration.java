package com.salomon.intranet;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Flyway finishes during initialization; this profile is a one-shot job. */
@Configuration(proxyBeanMethods = false)
@Profile("migrate")
class MigrationConfiguration {
    @Bean
    ApplicationRunner closeAfterMigration(ConfigurableApplicationContext context) {
        return args -> context.close();
    }
}
