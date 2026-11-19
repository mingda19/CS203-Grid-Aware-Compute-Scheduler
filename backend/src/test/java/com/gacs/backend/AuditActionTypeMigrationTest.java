package com.gacs.backend;

import com.gacs.backend.model.AuditActionType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ddl-auto=update never rewrites the ck_audit_action_type check on an existing database, so every new
 * AuditActionType value needs a migration (V9 for REVOKE, V12 for REMOVE_JOB). Needs no database, so it
 * runs on every build: it fails until the newest migration that sets the check lists every enum value.
 */
class AuditActionTypeMigrationTest {
    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern CHECK = Pattern.compile(
        "ADD CONSTRAINT ck_audit_action_type CHECK\\s*\\(action_type IN \\(([^)]*)\\)\\)");

    @Test void newestAuditCheckAllowsEveryActionType() throws Exception {
        Resource newest = null;
        String newestSql = null;
        int newestVersion = -1;
        for (Resource migration : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
            var match = VERSION.matcher(migration.getFilename());
            if (!match.matches() || Integer.parseInt(match.group(1)) < newestVersion) continue;
            String sql = migration.getContentAsString(StandardCharsets.UTF_8);
            if (!CHECK.matcher(sql).find()) continue;
            newest = migration; newestSql = sql; newestVersion = Integer.parseInt(match.group(1));
        }
        assertThat(newest).as("a migration that sets ck_audit_action_type").isNotNull();
        var allowed = new TreeSet<String>();
        var list = CHECK.matcher(newestSql);
        assertThat(list.find()).isTrue();
        for (String value : list.group(1).split(",")) allowed.add(value.trim().replace("'", ""));
        Set<String> expected = new TreeSet<>(Arrays.stream(AuditActionType.values()).map(Enum::name).toList());
        assertThat(allowed).as("values allowed by " + newest.getFilename()).isEqualTo(expected);
    }
}
