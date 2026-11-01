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
