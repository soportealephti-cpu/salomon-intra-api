package com.salomon.intranet;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Refuse startup against the wrong database, an unmigrated schema or a DDL role. */
@Component
@Profile("!migrate")
class DatabaseConnectionVerifier implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    DatabaseConnectionVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"salomon_intra".equals(jdbc.queryForObject("SELECT current_database()", String.class))) {
            throw new IllegalStateException("API database must be salomon_intra");
        }
        Boolean excessivePrivileges = jdbc.queryForObject("""
                SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
                       OR has_schema_privilege(current_user, 'intra', 'CREATE')
                FROM pg_roles WHERE rolname = current_user
                """, Boolean.class);
        if (!Boolean.FALSE.equals(excessivePrivileges)) {
            throw new IllegalStateException("API requires a restricted runtime database role");
        }
        if (!"salomon-intra-api".equals(jdbc.queryForObject(
                "SELECT service FROM intra.schema_metadata WHERE id = 1", String.class))) {
            throw new IllegalStateException("Intranet schema initialization is incomplete");
        }
    }
}
