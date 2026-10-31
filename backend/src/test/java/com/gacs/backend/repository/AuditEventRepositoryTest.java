package com.gacs.backend.repository;

import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AuditEventRepositoryTest {

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("AC4.1 — Persist and retrieve AuditEvent with all attributes")
    void testSaveAndRetrieveAuditEvent() {
        String runId = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44";
        String hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String rationale = "Approved schedule after verifying cooling margin and spot price forecast.";

        AuditEvent event = AuditEvent.builder()
                .timestamp(Instant.parse("2026-10-28T09:30:00Z"))
                .actor("operator@gacs.internal")
                .actionType(AuditActionType.APPROVE)
                .runId(runId)
                .scheduleHash(hash)
                .operatorRationale(rationale)
                .build();

        AuditEvent saved = auditEventRepository.save(event);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTimestamp()).isNotNull();
        assertThat(saved.getActor()).isEqualTo("operator@gacs.internal");
        assertThat(saved.getActionType()).isEqualTo(AuditActionType.APPROVE);
        assertThat(saved.getRunId()).isEqualTo(runId);
        assertThat(saved.getScheduleHash()).isEqualTo(hash);
        assertThat(saved.getOperatorRationale()).isEqualTo(rationale);

        Optional<AuditEvent> retrievedOpt = auditEventRepository.findById(saved.getId());
        assertThat(retrievedOpt).isPresent();
        AuditEvent retrieved = retrievedOpt.get();
        assertThat(retrieved.getActor()).isEqualTo("operator@gacs.internal");
        assertThat(retrieved.getActionType()).isEqualTo(AuditActionType.APPROVE);
        assertThat(retrieved.getRunId()).isEqualTo(runId);
        assertThat(retrieved.getScheduleHash()).isEqualTo(hash);
        assertThat(retrieved.getOperatorRationale()).isEqualTo(rationale);
    }

    @Test
    @DisplayName("AC4.1 / AC5.1 — Audit events remain unchanged after managed entity mutations")
    void testImmutabilityOnUpdate() {
        AuditEvent event = AuditEvent.builder()
                .timestamp(Instant.parse("2026-10-28T09:30:00Z"))
                .actor("operator@gacs.internal")
                .actionType(AuditActionType.APPROVE)
                .runId("run-12345")
                .scheduleHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .operatorRationale("Initial approval")
                .build();

        AuditEvent saved = auditEventRepository.saveAndFlush(event);
        Long id = saved.getId();
        ReflectionTestUtils.setField(saved, "operatorRationale", "Tampered rationale");
        assertThat(saved.getOperatorRationale()).isEqualTo("Tampered rationale");

        auditEventRepository.flush();
        entityManager.clear();

        AuditEvent reloaded = auditEventRepository.findById(id).orElseThrow();
        assertThat(reloaded.getOperatorRationale()).isEqualTo("Initial approval");
    }

    @Test
    @DisplayName("AC4.1 / AC5.1 — Audit events are append-only; delete attempts throw UnsupportedOperationException")
    void testImmutabilityOnDelete() {
        AuditEvent event = AuditEvent.builder()
                .timestamp(Instant.parse("2026-10-28T09:30:00Z"))
                .actor("operator@gacs.internal")
                .actionType(AuditActionType.REJECT)
                .runId("run-delete-test")
                .scheduleHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .operatorRationale("Rejected due to peak pricing spike")
                .build();

        AuditEvent saved = auditEventRepository.saveAndFlush(event);

        // Verify entity @PreRemove throws UnsupportedOperationException
        assertThatThrownBy(() -> {
            entityManager.remove(saved);
            entityManager.flush();
        }).isInstanceOf(UnsupportedOperationException.class)
          .hasMessage("Audit events are append-only. Deletions are forbidden.");
    }

    @Test
    @DisplayName("AC5.1 — Repository exposes no delete methods")
    void testRepositoryHasNoDeleteMethods() {
        assertThat(AuditEventRepository.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
    }

    @Test
    @DisplayName("Find audit events by runId ordered chronologically descending")
    void testFindByRunIdOrderByTimestampDesc() {
        String runId = "run-filter-test";
        String otherRunId = "run-other-test";
        String hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        AuditEvent e1 = AuditEvent.builder()
                .actor("operator1@gacs.internal")
                .actionType(AuditActionType.REVISE)
                .timestamp(Instant.now().minusSeconds(60))
                .runId(runId)
                .scheduleHash(hash)
                .operatorRationale("First revision")
                .build();

        AuditEvent e2 = AuditEvent.builder()
                .actor("operator2@gacs.internal")
                .actionType(AuditActionType.APPROVE)
                .timestamp(Instant.now())
                .runId(runId)
                .scheduleHash(hash)
                .operatorRationale("Final approval")
                .build();

        AuditEvent eOther = AuditEvent.builder()
                .actor("operator3@gacs.internal")
                .actionType(AuditActionType.REJECT)
                .timestamp(Instant.now())
                .runId(otherRunId)
                .scheduleHash(hash)
                .operatorRationale("Unrelated run rejection")
                .build();

        auditEventRepository.saveAll(List.of(e1, e2, eOther));

        List<AuditEvent> events = auditEventRepository.findByRunIdOrderByTimestampDesc(runId);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getOperatorRationale()).isEqualTo("Final approval");
        assertThat(events.get(1).getOperatorRationale()).isEqualTo("First revision");
    }
}
