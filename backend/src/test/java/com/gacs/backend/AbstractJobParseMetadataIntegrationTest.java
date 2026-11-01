package com.gacs.backend;

import com.gacs.backend.model.ConfirmationOutcome;
import com.gacs.backend.model.Job;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.config.DataInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.gacs.backend.service.DataFreshnessService;
import com.gacs.backend.service.ForecastConfidenceAlertService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Shared committed CRUD assertions, run on H2 and on configured PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "parse-operator@datacenter.io")
abstract class AbstractJobParseMetadataIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JobRepository repository;
    @Autowired JdbcTemplate jdbc;

    // PostgreSQL coverage creates jobs only; background checks and admin seeding are unrelated.
    @MockitoBean DataFreshnessService dataFreshnessService;
    @MockitoBean ForecastConfidenceAlertService forecastConfidenceAlertService;
    @MockitoBean DataInitializer dataInitializer;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE jobs RESTART IDENTITY");
    }

    private static final String OWNER = "parse-operator@datacenter.io";
    private static final LocalDateTime PARSED_AT = LocalDateTime.of(2026, 10, 20, 9, 30);
    protected static final String SOURCE = "  Finish tomorrow.\nKeep original wording!  ";
    private static final String BASE = """
            {"name":"Parse job","cpuHours":4,"gpuHours":0,
             "originalStartTime":"2026-10-20T10:00:00",
             "deadline":"2026-10-21T18:00:00","flexibilityWindowHours":2
            """;

    private ResultActions create(String fields) throws Exception {
        return mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(BASE + fields + "}"));
    }

    private ResultActions update(long id, String fields) throws Exception {
        return mvc.perform(put("/api/jobs/{id}", id).contentType(MediaType.APPLICATION_JSON).content(BASE + fields + "}"));
    }

    protected Job savedParse() {
        return repository.saveAndFlush(Job.builder().ownerEmail(OWNER).name("Parsed job").cpuHours(4.0)
                .originalStartTime(PARSED_AT.plusHours(1)).deadline(PARSED_AT.plusDays(1))
                .rawConstraints(SOURCE).parsedAt(PARSED_AT).confirmationOutcome(ConfirmationOutcome.CONFIRMED)
                .build());
    }

    private Job reload(long id) {
        // No test transaction: each repository read opens a fresh persistence context.
        return repository.findById(id).orElseThrow();
    }

    @ParameterizedTest
    @EnumSource(ConfirmationOutcome.class)
    void persistsAndReturnsEveryOutcome(ConfirmationOutcome outcome) throws Exception {
        create(",\"rawConstraints\":\"  Finish tomorrow.\\nKeep original wording!  \","
                + "\"parsedAt\":\"2026-10-20T09:30:00\",\"confirmationOutcome\":\"" + outcome + "\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rawConstraints").value(SOURCE))
                .andExpect(jsonPath("$.data.parsedAt").value("2026-10-20T09:30:00"))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(outcome.name()));
        Job stored = reload(repository.findByOwnerEmailOrderByOriginalStartTimeAsc(OWNER).getFirst().getId());
        assertThat(stored.getRawConstraints()).isEqualTo(SOURCE);
        assertThat(stored.getParsedAt()).isEqualTo(PARSED_AT);
        assertThat(stored.getConfirmationOutcome()).isEqualTo(outcome);
        mvc.perform(get("/api/jobs/{id}", stored.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationOutcome").value(outcome.name()));
        mvc.perform(get("/api/jobs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].parsedAt").value("2026-10-20T09:30:00"));
    }

    @ParameterizedTest
    @EnumSource(ConfirmationOutcome.class)
    void roundTripsCreationAndReplacementEvidence(ConfirmationOutcome outcome) throws Exception {
        String createdAt = "2026-10-20T09:30:00.123456";
        String updatedAt = "2026-10-20T09:45:00.654321";
        create(",\"rawConstraints\":\"  Finish tomorrow.\\nKeep original wording!  \","
                + "\"parsedAt\":\"" + createdAt + "\",\"confirmationOutcome\":\"" + outcome + "\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rawConstraints").value(SOURCE))
                .andExpect(jsonPath("$.data.parsedAt").value(createdAt))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(outcome.name()));
        long id = repository.findByOwnerEmailOrderByOriginalStartTimeAsc(OWNER).getFirst().getId();
        assertEvidenceEverywhere(id, SOURCE, createdAt, outcome);

        String replacement = "  Updated source.\nPreserve this too!  ";
        update(id, ",\"rawConstraints\":\"  Updated source.\\nPreserve this too!  \","
                + "\"parsedAt\":\"" + updatedAt + "\",\"confirmationOutcome\":\"" + outcome + "\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawConstraints").value(replacement))
                .andExpect(jsonPath("$.data.parsedAt").value(updatedAt))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(outcome.name()));
        assertEvidenceEverywhere(id, replacement, updatedAt, outcome);
    }

    protected void assertEvidenceEverywhere(long id, String source, String timestamp,
                                          ConfirmationOutcome outcome) throws Exception {
        Job stored = reload(id);
        assertThat(stored.getRawConstraints()).isEqualTo(source);
        assertThat(stored.getParsedAt()).isEqualTo(LocalDateTime.parse(timestamp));
        assertThat(stored.getConfirmationOutcome()).isEqualTo(outcome);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
        assertThat(row.get("raw_constraints")).isEqualTo(source);
        assertThat(((java.sql.Timestamp) row.get("parsed_at")).toLocalDateTime())
                .isEqualTo(LocalDateTime.parse(timestamp));
        assertThat(row.get("confirmation_outcome")).isEqualTo(outcome.name());
        mvc.perform(get("/api/jobs/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawConstraints").value(source))
                .andExpect(jsonPath("$.data.parsedAt").value(timestamp))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(outcome.name()));
        mvc.perform(get("/api/jobs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].rawConstraints").value(source))
                .andExpect(jsonPath("$.data[0].parsedAt").value(timestamp))
                .andExpect(jsonPath("$.data[0].confirmationOutcome").value(outcome.name()));
    }

    @Test
    void unparsedJobsHaveNoFabricatedMetadata() throws Exception {
        create("").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rawConstraints").value(nullValue()))
                .andExpect(jsonPath("$.data.parsedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(nullValue()));
        Job stored = reload(repository.findByOwnerEmailOrderByOriginalStartTimeAsc(OWNER).getFirst().getId());
        assertThat(stored.getParsedAt()).isNull();
        assertThat(stored.getConfirmationOutcome()).isNull();
        update(stored.getId(), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawConstraints").value(nullValue()))
                .andExpect(jsonPath("$.data.parsedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(nullValue()));
        mvc.perform(get("/api/jobs/{id}", stored.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawConstraints").value(nullValue()))
                .andExpect(jsonPath("$.data.parsedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(nullValue()));
        mvc.perform(get("/api/jobs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rawConstraints").value(nullValue()))
                .andExpect(jsonPath("$.data[0].parsedAt").value(nullValue()))
                .andExpect(jsonPath("$.data[0].confirmationOutcome").value(nullValue()));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", stored.getId());
        assertThat(row.get("raw_constraints")).isNull();
        assertThat(row.get("parsed_at")).isNull();
        assertThat(row.get("confirmation_outcome")).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ConfirmationOutcome.class, names = {"PENDING", "REJECTED"})
    void validSchedulingEditsDoNotRequireConfirmation(ConfirmationOutcome outcome) throws Exception {
        Job seed = savedParse();
        seed.setConfirmationOutcome(outcome);
        repository.saveAndFlush(seed);
        String payload = BASE.replace("\"cpuHours\":4", "\"cpuHours\":8") + "}";
        mvc.perform(put("/api/jobs/{id}", seed.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(payload)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cpuHours").value(8))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(outcome.name()));
        assertThat(reload(seed.getId()).getCpuHours()).isEqualTo(8.0);
        assertEvidenceEverywhere(seed.getId(), SOURCE, "2026-10-20T09:30:00", outcome);
    }

    @Test
    void defaultsToPendingOnlyWhenParseTimeIsSupplied() throws Exception {
        create(",\"rawConstraints\":\"Finish tomorrow\",\"parsedAt\":\"2026-10-20T09:30:00\"")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.confirmationOutcome").value("PENDING"));
    }

    @Test
    void unrelatedEditsAndExemptionTogglePreserveProvenance() throws Exception {
        long id = savedParse().getId();
        update(id, "").andExpect(status().isOk());
        update(id, ",\"isExempt\":true").andExpect(status().isOk());
        Job stored = reload(id);
        assertThat(stored.isExempt()).isTrue();
        assertThat(stored.getRawConstraints()).isEqualTo(SOURCE);
        assertThat(stored.getParsedAt()).isEqualTo(PARSED_AT);
        assertThat(stored.getConfirmationOutcome()).isEqualTo(ConfirmationOutcome.CONFIRMED);
    }

    @Test
    void confirmationCanChangeOrBeExplicitlyCleared() throws Exception {
        long id = savedParse().getId();
        update(id, ",\"confirmationOutcome\":\"REJECTED\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationOutcome").value("REJECTED"));
        update(id, ",\"confirmationOutcome\":null").andExpect(status().isOk());
        Job stored = reload(id);
        assertThat(stored.getParsedAt()).isEqualTo(PARSED_AT);
        assertThat(stored.getConfirmationOutcome()).isNull();
        update(id, ",\"parsedAt\":null").andExpect(status().isOk());
        assertThat(reload(id).getParsedAt()).isNull();
    }

    @Test
    void sourceChangesResetMetadataAndAllowReplacementOrClearing() throws Exception {
        long id = savedParse().getId();
        update(id, ",\"rawConstraints\":\"New source\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parsedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(nullValue()));
        update(id, ",\"rawConstraints\":\"Reparsed source\",\"parsedAt\":\"2026-10-20T09:45:00\"")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmationOutcome").value("PENDING"));
        assertThat(reload(id).getParsedAt()).isEqualTo(PARSED_AT.plusMinutes(15));
        update(id, ",\"rawConstraints\":null").andExpect(status().isOk());
        Job stored = reload(id);
        assertThat(stored.getRawConstraints()).isNull();
        assertThat(stored.getParsedAt()).isNull();
        assertThat(stored.getConfirmationOutcome()).isNull();
    }

    @Test
    void unchangedSourcePreservesMetadataAndReparseResetsDecision() throws Exception {
        long id = savedParse().getId();
        update(id, ",\"rawConstraints\":\"  Finish tomorrow.\\nKeep original wording!  \"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationOutcome").value("CONFIRMED"));
        update(id, ",\"parsedAt\":\"2026-10-20T09:45:00\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmationOutcome").value("PENDING"));
        assertThat(reload(id).getParsedAt()).isEqualTo(PARSED_AT.plusMinutes(15));
        update(id, ",\"parsedAt\":null,\"confirmationOutcome\":null").andExpect(status().isOk());
        Job stored = reload(id);
        assertThat(stored.getRawConstraints()).isEqualTo(SOURCE);
        assertThat(stored.getParsedAt()).isNull();
        assertThat(stored.getConfirmationOutcome()).isNull();
    }

    @Test
    void acceptsMaximumSourceLengthAndExplicitNullDecision() throws Exception {
        create(",\"rawConstraints\":\"" + "x".repeat(1000)
                + "\",\"parsedAt\":\"2026-10-20T09:30:00\",\"confirmationOutcome\":null")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.confirmationOutcome").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ",\"rawConstraints\":\"source\",\"parsedAt\":\"yesterday\"",
            ",\"rawConstraints\":\"source\",\"confirmationOutcome\":\"UNKNOWN\"",
            ",\"rawConstraints\":\"source\",\"confirmationOutcome\":\"CONFIRMED\"",
            ",\"parsedAt\":\"2026-10-20T09:30:00\"",
            ",\"rawConstraints\":\"   \",\"parsedAt\":\"2026-10-20T09:30:00\""
    })
    void rejectsInvalidMetadata(String fields) throws Exception {
        create(fields).andExpect(status().isBadRequest());
        assertThat(repository.findByOwnerEmailOrderByOriginalStartTimeAsc(OWNER)).isEmpty();
    }

    @Test
    void invalidUpdateDoesNotChangeStoredMetadata() throws Exception {
        long id = savedParse().getId();
        update(id, ",\"parsedAt\":null").andExpect(status().isBadRequest());
        assertThat(reload(id).getParsedAt()).isEqualTo(PARSED_AT);
        update(id, ",\"rawConstraints\":\"" + "x".repeat(1001) + "\"").andExpect(status().isBadRequest());
        assertThat(reload(id).getRawConstraints()).isEqualTo(SOURCE);
    }

    static Stream<Arguments> invalidUpdates() {
        String[] changes = {
                "{\"name\":\"   \"}",
                "{\"name\":\"" + "x".repeat(256) + "\"}",
                "{\"cpuHours\":-1}", "{\"gpuHours\":-1}",
                "{\"cpuHours\":0,\"gpuHours\":0}",
                "{\"cpuHours\":\"NaN\"}", "{\"gpuHours\":\"Infinity\"}",
                "{\"originalStartTime\":null}", "{\"deadline\":null}",
                "{\"deadline\":\"2026-10-20T09:00:00\"}",
                "{\"flexibilityWindowHours\":-1}",
                "{\"status\":\"UNKNOWN\"}", "{\"priority\":\"UNKNOWN\"}",
                "{\"parsedAt\":\"yesterday\"}",
                "{\"confirmationOutcome\":\"UNKNOWN\"}",
                "{\"parsedAt\":null}",
                "{\"rawConstraints\":\"   \",\"parsedAt\":\"2026-10-20T09:30:00\"}",
                "{\"rawConstraints\":null,\"confirmationOutcome\":\"CONFIRMED\"}",
                "{\"rawConstraints\":\"" + "x".repeat(1001) + "\"}"
        };
        return Stream.of(ConfirmationOutcome.values())
                .flatMap(outcome -> Stream.of(changes).map(change -> Arguments.of(outcome, change)));
    }

    @ParameterizedTest
    @MethodSource("invalidUpdates")
    void invalidUpdatesLeaveEntireCommittedJobUnchanged(ConfirmationOutcome outcome, String changes)
            throws Exception {
        Job seed = savedParse();
        seed.setConfirmationOutcome(outcome);
        repository.saveAndFlush(seed);
        long id = seed.getId();
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode request = (ObjectNode) mapper.readTree(BASE + "}");
        request.put("name", "This edit must not persist");
        request.setAll((ObjectNode) mapper.readTree(changes));
        mvc.perform(put("/api/jobs/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(request.toString())).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id)).isEqualTo(before);
        assertEvidenceEverywhere(id, SOURCE, "2026-10-20T09:30:00", outcome);
    }

    @Test
    void metadataRemainsScopedToOwner() throws Exception {
        long id = savedParse().getId();
        mvc.perform(get("/api/jobs/{id}", id).with(user("other@datacenter.io"))).andExpect(status().isNotFound());
        mvc.perform(put("/api/jobs/{id}", id).with(user("other@datacenter.io"))
                .contentType(MediaType.APPLICATION_JSON).content(BASE + ",\"confirmationOutcome\":\"REJECTED\"}"))
                .andExpect(status().isNotFound());
        assertThat(reload(id).getConfirmationOutcome()).isEqualTo(ConfirmationOutcome.CONFIRMED);
    }
}
