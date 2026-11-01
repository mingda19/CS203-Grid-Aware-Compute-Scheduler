package com.gacs.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Opt-in PostgreSQL verification; creates and drops only a randomly named test schema. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
class ScheduleLifecycleMigrationPostgresTest {
    @Test void rolloutIsIdempotentPreservesHistoryAndClosesLegacyApproval() throws Exception {
        String schema = "schedule_lifecycle_test_" + UUID.randomUUID().toString().replace("-", "");
        String sql = new ClassPathResource("db/migration/V5__schedule_approval_lifecycle.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        try (var connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "postgres"),
                System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                statement.execute("CREATE TABLE jobs(id bigint PRIMARY KEY); CREATE TABLE facilities(id bigint PRIMARY KEY);");
                statement.execute("CREATE TABLE optimization_plans(run_id varchar(36) PRIMARY KEY, result_json text, "
                    + "review_state varchar(30) CHECK (review_state IN ('PENDING','APPROVED','REJECTED','REVISION_REQUESTED')))");
                statement.execute("CREATE TABLE workload_schedules(id bigint PRIMARY KEY); CREATE TABLE audit_events(evidence text)");
                statement.execute("INSERT INTO jobs VALUES(1); INSERT INTO facilities VALUES(1); "
                    + "INSERT INTO optimization_plans VALUES('legacy','immutable-result','APPROVED'); "
                    + "INSERT INTO workload_schedules VALUES(1); INSERT INTO audit_events VALUES('immutable-decision')");
                statement.execute(sql);
                String first;
                try (var row = statement.executeQuery("SELECT review_state || ':' || invalidated_at::text FROM optimization_plans WHERE run_id='legacy'")) {
                    assertThat(row.next()).isTrue(); first = row.getString(1); assertThat(first).startsWith("INVALIDATED:");
                }
                statement.execute("INSERT INTO optimization_plans(run_id,result_json,review_state,input_snapshot) "
                    + "VALUES('current','new-result','APPROVED','snapshot')");
                statement.execute(sql);
                try (var row = statement.executeQuery("SELECT review_state || ':' || invalidated_at::text, result_json FROM optimization_plans WHERE run_id='legacy'")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo(first);
                    assertThat(row.getString(2)).isEqualTo("immutable-result");
                }
                try (var row = statement.executeQuery("SELECT review_state FROM optimization_plans WHERE run_id='current'")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("APPROVED");
                }
                try (var row = statement.executeQuery("SELECT scheduling_revision FROM jobs WHERE id=1")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getLong(1)).isZero();
                }
                try (var row = statement.executeQuery("SELECT evidence FROM audit_events")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("immutable-decision");
                }
                statement.execute("INSERT INTO workload_schedules(id,run_id,job_id) VALUES(2,'current',1)");
                assertThatThrownBy(() -> statement.execute("INSERT INTO workload_schedules(id,run_id,job_id) VALUES(3,'current',1)"))
                    .isInstanceOf(SQLException.class);
                // Nullable linkage keeps multiple old entries readable without granting approval.
                statement.execute("INSERT INTO workload_schedules(id) VALUES(4)");
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
