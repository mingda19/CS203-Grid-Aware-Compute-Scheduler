package com.gacs.backend;

import com.gacs.backend.config.SecurityConfig;
import com.gacs.backend.controller.ScheduleDecisionController;
import com.gacs.backend.exception.GlobalExceptionHandler;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.service.ScheduleReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every failure an operator decision - above all "Revoke approval" - can reach must come back as a
 * JSON {@code ApiResponse} with a status that says what actually went wrong. The operator report
 * behind this test class was a revoke that "did nothing" while an exception reached {@link
 * GlobalExceptionHandler}'s catch-all, which answers 500 "An unexpected error occurred" for anything
 * no narrower handler claims.
 *
 * <p>A 500 is not merely unhelpful here: the client cannot tell a transient race (retry) from an
 * unsupported endpoint (the server is older than the browser) from a plan that moved underneath the
 * decision (refresh), so it cannot tell the operator what to do, and the decision silently fails.
 * These tests pin the status and the envelope for each cause reachable from {@code
 * ScheduleReviewService#decide}; the production mappings they cover are the handlers added to
 * {@link GlobalExceptionHandler} alongside this class.
 *
 * <p>Deliberately a {@code @WebMvcTest}: the point is the HTTP contract of the error, and the causes
 * (lock contention, a vanished owner row) are either hard or slow to provoke for real against a live
 * database, so the service is stubbed to throw them.
 */
@WebMvcTest(ScheduleDecisionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@WithMockUser(username = "operator@gacs.internal", roles = "USER")
class ScheduleDecisionErrorEnvelopeTest {

    private static final String RUN = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44";
    private static final String BODY = "{\"comment\":\"Halting: cooling margin lost.\"}";

    @Autowired private MockMvc mvc;
    @MockitoBean private ScheduleReviewService reviews;

    /**
     * Two operators (or a decision and an apply) racing on one run contend on the plan row's
     * {@code PESSIMISTIC_WRITE} lock. Spring surfaces that as a {@link
     * org.springframework.dao.ConcurrencyFailureException}, which had no handler and so answered 500.
     * 409 is both accurate and the status the review UI already turns into "refresh and retry".
     */
    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject", "revise", "revoke"})
    @DisplayName("Lock contention on a decision is 409 in the envelope, not 500")
    void lockContentionIsConflict(String action) throws Exception {
        when(reviews.decide(any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("could not obtain lock on row"));

        mvc.perform(post("/api/schedules/" + RUN + "/" + action)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "Another change to this record is in progress. Refresh and try again."));
    }

    /** The database refusing a constraint is a conflict with stored data, not a server fault. */
    @Test
    @DisplayName("A database constraint violation on revoke is 409 in the envelope")
    void constraintViolationIsConflict() throws Exception {
        when(reviews.decide(any(), eq(AuditActionType.REVOKE), any(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "ERROR: value too long for type character varying(50)"));

        mvc.perform(post("/api/schedules/" + RUN + "/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                // The driver's own text must not leak: it names columns, types and SQL.
                .andExpect(jsonPath("$.message").value(
                        "This change conflicts with data already stored. Refresh and try again."));
    }

    /**
     * A plan reads its owner through a LAZY association. If that user row is gone by the time the
     * proxy resolves, Hibernate throws {@link jakarta.persistence.EntityNotFoundException} - a
     * missing resource, so 404, not 500.
     */
    @Test
    @DisplayName("A vanished owner row on revoke is 404 in the envelope")
    void missingAssociationIsNotFound() throws Exception {
        when(reviews.decide(any(), eq(AuditActionType.REVOKE), any(), any()))
                .thenThrow(new jakarta.persistence.EntityNotFoundException("Unable to find User with id 42"));

        mvc.perform(post("/api/schedules/" + RUN + "/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("A record this request depends on no longer exists."));
    }

    /**
     * The leading hypothesis for the original report: the running server predates {@code /revoke}, so
     * the browser posts to a path that deployment does not have. That cannot be reproduced from the
     * current build - the endpoint exists - but the shape of the answer can be, with a path no build
     * has. Before, Spring's own error page replied and the body was not an {@code ApiResponse} at
     * all, so a client reading {@code message} off every response had nothing to show and the button
     * appeared to do nothing. The envelope is what lets it say something true instead.
     */
    @Test
    @DisplayName("An endpoint this server does not have is 404 in the envelope, naming the path")
    void unknownDecisionPathIsNotFoundInEnvelope() throws Exception {
        mvc.perform(post("/api/schedules/" + RUN + "/unrevoke")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No such endpoint")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("older version")));
    }

    /** A decision path reached with the wrong method answers 405 in the envelope, not an error page. */
    @Test
    @DisplayName("GET on a decision endpoint is 405 in the envelope")
    void wrongMethodIsMethodNotAllowedInEnvelope() throws Exception {
        mvc.perform(get("/api/schedules/" + RUN + "/revoke"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("is not supported for this endpoint")));
    }

    /**
     * The refusals the gate itself raises were already mapped; asserted here so the whole set of
     * statuses a revoke can produce is pinned in one place rather than inferred from three files.
     */
    @Test
    @DisplayName("Revoking a run that is not approved is 409 with its reason")
    void revokingAnUndecidedRunIsConflict() throws Exception {
        when(reviews.decide(any(), eq(AuditActionType.REVOKE), any(), any()))
                .thenThrow(new ScheduleExportRejectedException(
                        ScheduleExportRejectedException.Reason.ALREADY_DECIDED,
                        "Schedule run " + RUN + " is pending, not approved, so there is no approval to revoke"));

        mvc.perform(post("/api/schedules/" + RUN + "/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("ALREADY_DECIDED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("there is no approval to revoke")));
    }

    @Test
    @DisplayName("Revoking a run the caller does not own is 404 in the envelope")
    void revokingAnUnownedRunIsNotFound() throws Exception {
        when(reviews.decide(any(), eq(AuditActionType.REVOKE), any(), any()))
                .thenThrow(new ResourceNotFoundException("Optimization plan not found"));

        mvc.perform(post("/api/schedules/" + RUN + "/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Optimization plan not found"));
    }

    /** A JWT that outlived its account: 401, so the client re-authenticates rather than retrying. */
    @Test
    @DisplayName("Revoking as a user whose account is gone is 401 in the envelope")
    void revokingWithoutADatabaseUserIsUnauthorized() throws Exception {
        when(reviews.decide(any(), eq(AuditActionType.REVOKE), any(), any()))
                .thenThrow(new BadCredentialsException("Authenticated user no longer exists"));

        mvc.perform(post("/api/schedules/" + RUN + "/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Authenticated user no longer exists"));
    }

    /** A blank rationale is a 400 validation envelope, never a decision the gate sees. */
    @Test
    @DisplayName("Revoking with a blank rationale is 400 and never reaches the service")
    void blankRationaleIsValidationFailure() throws Exception {
        mvc.perform(post("/api/schedules/" + RUN + "/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    /** A malformed run id is a 400, not a 500 from UUID parsing. */
    @Test
    @DisplayName("A malformed run id on revoke is 400 in the envelope")
    void malformedRunIdIsBadRequest() throws Exception {
        mvc.perform(post("/api/schedules/not-a-uuid/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
