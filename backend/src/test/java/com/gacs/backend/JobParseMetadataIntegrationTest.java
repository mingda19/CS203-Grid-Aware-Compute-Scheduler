package com.gacs.backend;

import com.gacs.backend.model.ConfirmationOutcome;
import com.gacs.backend.model.Job;
import com.gacs.backend.repository.JobRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AC4.1: test JSON presence semantics through the service and database. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "parse-operator@datacenter.io")
class JobParseMetadataIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JobRepository repository;
    @Autowired EntityManager entityManager;

    private static final String OWNER = "parse-operator@datacenter.io";
    private static final LocalDateTime PARSED_AT = LocalDateTime.of(2026, 10, 20, 9, 30);
    private static final String SOURCE = "  Finish tomorrow.\nKeep original wording!  ";
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

    private Job savedParse() {
        return repository.saveAndFlush(Job.builder().ownerEmail(OWNER).name("Parsed job").cpuHours(4.0)
                .originalStartTime(PARSED_AT.plusHours(1)).deadline(PARSED_AT.plusDays(1))
                .rawConstraints(SOURCE).parsedAt(PARSED_AT).confirmationOutcome(ConfirmationOutcome.CONFIRMED)
                .build());
    }

    private Job reload(long id) {
        entityManager.flush();
        entityManager.clear();
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

    @Test
    void unparsedJobsHaveNoFabricatedMetadata() throws Exception {
        create("").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rawConstraints").value(nullValue()))
                .andExpect(jsonPath("$.data.parsedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.confirmationOutcome").value(nullValue()));
        Job stored = reload(repository.findByOwnerEmailOrderByOriginalStartTimeAsc(OWNER).getFirst().getId());
        assertThat(stored.getParsedAt()).isNull();
        assertThat(stored.getConfirmationOutcome()).isNull();
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
