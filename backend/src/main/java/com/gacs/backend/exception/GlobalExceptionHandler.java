package com.gacs.backend.exception;

import com.gacs.backend.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(MethodArgumentTypeMismatchException ex) {
        String expectedType = ex.getRequiredType() == null ? "the expected type" : ex.getRequiredType().getSimpleName();
        return ResponseEntity.badRequest().body(ApiResponse.fail("Parameter '" + ex.getName() + "' value '"
                + ex.getValue() + "' could not be converted to " + expectedType));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        ApiResponse<Map<String, String>> response = new ApiResponse<>(false, "Validation failed", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("Malformed JSON or invalid field value"));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        ApiResponse<Void> response = ApiResponse.fail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        ApiResponse<Void> response = ApiResponse.fail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ScheduleExportRejectedException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleExportRejected(ScheduleExportRejectedException ex) {
        // Always JSON: export requests ask for the file type in Accept, but a refusal is an error envelope.
        return ResponseEntity.status(ex.getReason().status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiResponse<>(false, ex.getMessage(), Map.of("reason", ex.getReason().name())));
    }

    @ExceptionHandler(OptimizationFailedException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleOptimizationFailed(OptimizationFailedException ex) {
        logger.error("Optimiser run {} failed", ex.getRunId(), ex.getCause());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiResponse<>(false,
                "The optimiser failed; nothing was scheduled", Map.of("runId", ex.getRunId())));
    }

    @ExceptionHandler(UnprocessableDataException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnprocessableData(UnprocessableDataException ex) {
        ApiResponse<Void> response = ApiResponse.fail(ex.getMessage());
        return ResponseEntity.status(422).body(response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        ApiResponse<Void> response = ApiResponse.fail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        ApiResponse<Void> response = ApiResponse.fail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * Lock contention and optimistic-lock loss, as a 409 rather than a 500.
     *
     * <p>Every schedule decision - approve, reject, revise, revoke - takes a {@code
     * PESSIMISTIC_WRITE} lock on the plan row ({@code OptimizationPlanRepository.findOwnedForUpdate})
     * after locking the operator row. Two operator actions racing on the same run, or a decision
     * racing the apply/export path, therefore surface as a {@link ConcurrencyFailureException}
     * subclass ({@code CannotAcquireLockException}, {@code PessimisticLockingFailureException},
     * {@code OptimisticLockingFailureException}). None of those had a handler, so they fell through
     * to the catch-all below and an operator pressing a decision button during a race got an opaque
     * "unexpected error" 500 - for a condition that is neither unexpected nor the server's fault, and
     * whose correct remedy is simply to retry.
     *
     * <p>409 is the status the UI already understands for a decision that could not be applied
     * because the plan moved under it: {@code useScheduleReview} turns a 409 into the "its approval
     * or validity changed - refresh plans" banner, which is exactly right here.
     */
    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrencyFailure(
            org.springframework.dao.ConcurrencyFailureException ex) {
        // Logged, not returned: the message can carry SQL and lock internals.
        logger.warn("Concurrent access refused a request", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(
                "Another change to this record is in progress. Refresh and try again."));
    }

    /**
     * A constraint the database refused, as a 409 rather than a 500: the caller's request conflicts
     * with data already stored, which is a client-visible conflict and not a server fault.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex) {
        logger.warn("Request rejected by a database constraint", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(
                "This change conflicts with data already stored. Refresh and try again."));
    }

    /**
     * A lazy association whose row has gone, as a 404 rather than a 500. A plan reads its owner
     * through a {@code LAZY} {@code @ManyToOne}, so a plan whose owner was deleted between the lock
     * and the read throws this when the proxy resolves. The resource genuinely no longer exists.
     */
    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(jakarta.persistence.EntityNotFoundException ex) {
        logger.warn("A referenced record no longer exists", ex);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("A record this request depends on no longer exists."));
    }

    /**
     * An unmapped path or a path that does not accept this method, in the standard envelope.
     *
     * <p>Without these two, Spring's own error page answers instead, and the body is the servlet
     * container's `{"timestamp":...,"status":404,"error":"Not Found","path":...}` or plain HTML -
     * neither of which is an {@link ApiResponse}. A client that reads `message` off every response
     * then has nothing to show, which is exactly what an operator sees when the browser is newer
     * than the server it is talking to and calls an endpoint that deployment does not have yet
     * (the reported "Revoke approval does nothing"). Answering in the envelope means the client can
     * always report *something* accurate, whichever side is out of date.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(
                "No such endpoint: " + ex.getHttpMethod() + " " + ex.getResourcePath()
                        + ". This server may be an older version than the client calling it."));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.fail(
                "Method " + ex.getMethod() + " is not supported for this endpoint."));
    }

    /**
     * Last resort for anything no more specific handler above caught. Logs the real exception
     * server-side (the only place its message, cause and stack trace belong) and returns a generic
     * body to the caller: {@code ex.getMessage()} can carry SQL text, file paths or other internals
     * that have no business leaving the server in an HTTP response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        logger.error("Unhandled exception reaching GlobalExceptionHandler", ex);
        ApiResponse<Void> response = ApiResponse.fail("An unexpected error occurred. Please try again or contact support.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
