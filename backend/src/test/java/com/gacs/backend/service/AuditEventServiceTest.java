package com.gacs.backend.service;

import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.repository.AuditEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @InjectMocks
    private AuditEventService auditEventService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthenticatedUser(String username) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username,
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @Test
    @DisplayName("AC4.1a — Actor identity extracted strictly from SecurityContextHolder principal")
    void testRecordEvent_ActorExtractedFromSecurityContext() {
        setAuthenticatedUser("operator@gacs.internal");

        AuditEventRequestDto request = new AuditEventRequestDto(
                AuditActionType.APPROVE,
                "run-777",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "Verified sufficient cooling and capacity margin"
        );

        when(auditEventRepository.save(any(AuditEvent.class))).thenAnswer(invocation -> {
            AuditEvent event = invocation.getArgument(0);
            return AuditEvent.builder()
                    .id(1L)
                    .actor(event.getActor())
                    .actionType(event.getActionType())
                    .timestamp(event.getTimestamp() != null ? event.getTimestamp() : Instant.now())
                    .runId(event.getRunId())
                    .scheduleHash(event.getScheduleHash())
                    .operatorRationale(event.getOperatorRationale())
                    .build();
        });

        AuditEventResponseDto response = auditEventService.recordEvent(request);

        assertThat(response).isNotNull();
        assertThat(response.actor()).isEqualTo("operator@gacs.internal");
        assertThat(response.actionType()).isEqualTo(AuditActionType.APPROVE);
        assertThat(response.runId()).isEqualTo("run-777");
        assertThat(response.scheduleHash()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(response.operatorRationale()).isEqualTo("Verified sufficient cooling and capacity margin");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("operator@gacs.internal");
    }

    @Test
    @DisplayName("AC4.1a — Unauthenticated call throws AccessDeniedException")
    void testRecordEvent_UnauthenticatedFails() {
        // No authentication in SecurityContext
        SecurityContextHolder.clearContext();

        AuditEventRequestDto request = new AuditEventRequestDto(
                AuditActionType.APPROVE,
                "run-777",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "Some rationale"
        );

        assertThatThrownBy(() -> auditEventService.recordEvent(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Unauthenticated caller");

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    @DisplayName("AC4.1a — Anonymous user call throws AccessDeniedException")
    void testRecordEvent_AnonymousUserFails() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", Collections.singletonList(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        );
        context.setAuthentication(anonymous);
        SecurityContextHolder.setContext(context);

        AuditEventRequestDto request = new AuditEventRequestDto(
                AuditActionType.APPROVE,
                "run-777",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "Some rationale"
        );

        assertThatThrownBy(() -> auditEventService.recordEvent(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Unauthenticated caller");

        verifyNoInteractions(auditEventRepository);
    }

    @Test
    @DisplayName("Retrieve audit events by runId")
    void testGetEventsByRunId() {
        AuditEvent event = AuditEvent.builder()
                .id(1L)
                .actor("operator@gacs.internal")
                .actionType(AuditActionType.DISPATCH)
                .timestamp(Instant.now())
                .runId("run-999")
                .scheduleHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .operatorRationale("Dispatch confirmed")
                .build();

        when(auditEventRepository.findByRunIdOrderByTimestampDesc("run-999"))
                .thenReturn(List.of(event));

        List<AuditEventResponseDto> result = auditEventService.getEventsByRunId("run-999");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).runId()).isEqualTo("run-999");
        assertThat(result.get(0).actionType()).isEqualTo(AuditActionType.DISPATCH);
    }

    @Test
    @DisplayName("Retrieve audit event by ID")
    void testGetEventById() {
        AuditEvent event = AuditEvent.builder()
                .id(42L)
                .actor("admin@gacs.internal")
                .actionType(AuditActionType.OVERRIDE)
                .timestamp(Instant.now())
                .runId("run-override")
                .scheduleHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .operatorRationale("Override manual adjustment")
                .build();

        when(auditEventRepository.findById(42L)).thenReturn(Optional.of(event));

        AuditEventResponseDto result = auditEventService.getEventById(42L);

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.actor()).isEqualTo("admin@gacs.internal");
        assertThat(result.actionType()).isEqualTo(AuditActionType.OVERRIDE);
    }
}
