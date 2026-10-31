package com.gacs.backend;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** AC5.1: run the shared CRUD assertions against an explicitly configured test database. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class JobParseMetadataPostgresIntegrationTest extends AbstractJobParseMetadataIntegrationTest {
    private static final String SCHEMA = "job_parse_test_" + UUID.randomUUID().toString().replace("-", "");
    private static boolean schemaCreated;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry properties) throws Exception {
        // Create only our own schema; never create, truncate, or migrate existing application tables.
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        executeSql("db/jobs-baseline.sql");
        executeSql("db/migration/V2__add_job_priority.sql");
        executeSql("db/migration/V3__add_job_parse_metadata.sql");
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", JobParseMetadataPostgresIntegrationTest::username);
        properties.add("spring.datasource.password", JobParseMetadataPostgresIntegrationTest::password);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        properties.add("spring.sql.init.mode", () -> "never");
    }

    private static String username() {
        return System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "postgres");
    }

    private static String password() {
        return System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "");
    }

    private static Connection connect() throws Exception {
        String url = System.getenv("TEST_POSTGRES_URL");
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("TEST_POSTGRES_URL must be a PostgreSQL JDBC URL");
        }
        return DriverManager.getConnection(url, username(), password());
    }

    private static void executeSql(String resource) throws Exception {
        // Execute whole files to preserve PostgreSQL DO $$ blocks.
        String sql = new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
        try (var connection = connect(); var statement = connection.createStatement()) {
            connection.setSchema(SCHEMA);
            statement.execute(sql);
        }
    }

    @AfterAll
    static void removeTestSchema() throws Exception {
        if (schemaCreated) {
            try (var connection = connect(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    @Test
    void schemaScriptsAreIdempotentAndPreserveEvidence() throws Exception {
        long id = savedParse().getId();
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
        executeSql("db/migration/V2__add_job_priority.sql");
        executeSql("db/migration/V3__add_job_parse_metadata.sql");
        assertThat(jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id)).isEqualTo(before);
        assertEvidenceEverywhere(id, SOURCE, "2026-10-20T09:30:00",
                com.gacs.backend.model.ConfirmationOutcome.CONFIRMED);
    }
}
