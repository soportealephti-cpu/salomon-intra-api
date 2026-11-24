package com.salomon.intranet;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntranetApiApplicationTests {
    private static final EmbeddedPostgres POSTGRES;
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Flyway FLYWAY;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder()
                    .setServerConfig("unix_socket_directories", "").start();
            try (var c = POSTGRES.getPostgresDatabase().getConnection(); var s = c.createStatement()) {
                s.execute("CREATE DATABASE salomon_intra");
            }
            String url = "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/salomon_intra";
            try (var c = DriverManager.getConnection(url, "postgres", ""); var s = c.createStatement()) {
                s.execute(Files.readString(Path.of("deploy/postgres/bootstrap.sql")));
                s.execute("ALTER ROLE salomon_intra_app LOGIN PASSWORD '" + PASSWORD + "'");
                s.execute("ALTER ROLE salomon_intra_migrator LOGIN PASSWORD '" + PASSWORD + "'");
            }
            FLYWAY = Flyway.configure().dataSource(url, "salomon_intra_migrator", PASSWORD)
                    .defaultSchema("flyway").schemas("flyway").createSchemas(false)
                    .cleanDisabled(true).load();
            FLYWAY.migrate();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:"
                + POSTGRES.getPort() + "/salomon_intra?currentSchema=intra");
        registry.add("spring.datasource.username", () -> "salomon_intra_app");
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test
    void connectsAsRuntimeUserToPostgres17AndReadsMigratedTable() {
        assertEquals("salomon_intra", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("salomon_intra_app", jdbc.queryForObject("SELECT current_user", String.class));
        assertTrue(jdbc.queryForObject("SHOW server_version", String.class).startsWith("17."));
        assertEquals("salomon-intra-api", jdbc.queryForObject("SELECT service FROM intra.schema_metadata", String.class));
    }

    @Test
    void runtimeCannotCreateObjectsOrChangeFlywayHistory() {
        assertThrows(Exception.class, () -> jdbc.execute("CREATE TABLE intra.forbidden (id int)"));
        assertThrows(Exception.class, () -> jdbc.execute("SELECT * FROM flyway.flyway_schema_history"));
        assertFalse(jdbc.queryForObject("SELECT has_database_privilege(current_user, 'salomon_intra', 'CREATE')", Boolean.class));
    }

    @Test
    void migrationValidatesAndSecondRunDoesNotChangeSchema() {
        FLYWAY.validate();
        assertEquals(0, FLYWAY.migrate().migrationsExecuted);
    }

    @Test
    void startupRejectsMigrationRoleAndWrongDatabase() {
        String base = "jdbc:postgresql://localhost:" + POSTGRES.getPort();
        var migrationJdbc = new JdbcTemplate(new DriverManagerDataSource(
                base + "/salomon_intra", "salomon_intra_migrator", PASSWORD));
        assertThrows(IllegalStateException.class,
                () -> new DatabaseConnectionVerifier(migrationJdbc).run(null));
        var otherDatabaseJdbc = new JdbcTemplate(new DriverManagerDataSource(
                base + "/postgres", "postgres", ""));
        assertThrows(IllegalStateException.class,
                () -> new DatabaseConnectionVerifier(otherDatabaseJdbc).run(null));
    }

    @Test
    void databaseHealthAndReadinessReturnUpWithoutConnectionDetails() throws Exception {
        for (String path : new String[]{"/actuator/health/database", "/actuator/health/readiness"}) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("{\"status\":\"UP\"}", response.body());
            assertFalse(response.body().contains(PASSWORD));
        }
    }

    @Test
    void migrationProfileRunsAsOneShotAndCloses() {
        try (var context = new SpringApplicationBuilder(IntranetApiApplication.class)
                .web(WebApplicationType.NONE).profiles("migrate").run(
                        "--spring.datasource.url=jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/salomon_intra",
                        "--spring.datasource.username=salomon_intra_migrator",
                        "--spring.datasource.password=" + PASSWORD)) {
            assertFalse(context.isActive());
        }
    }

    @Test
    void databaseHealthReturns503WhenDatabaseCannotBeReached() throws Exception {
        try (var c = POSTGRES.getPostgresDatabase().getConnection(); var s = c.createStatement()) {
            s.execute("ALTER ROLE salomon_intra_app NOLOGIN");
            s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='salomon_intra_app'");
            try {
                var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/actuator/health/database"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(503, response.statusCode());
                assertEquals("{\"status\":\"DOWN\"}", response.body());
            } finally {
                s.execute("ALTER ROLE salomon_intra_app LOGIN");
            }
        }
    }
}
