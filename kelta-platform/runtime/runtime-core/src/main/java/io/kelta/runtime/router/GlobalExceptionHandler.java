package io.kelta.runtime.router;

import io.kelta.jsonapi.JsonApiError;
import io.kelta.runtime.query.InvalidQueryException;
import io.kelta.runtime.storage.ReferencedRecordConflictException;
import io.kelta.runtime.storage.StaleWriteException;
import io.kelta.runtime.storage.StorageException;
import io.kelta.runtime.storage.StorageQueryException;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import io.kelta.runtime.validation.DuplicateDefaultException;
import io.kelta.runtime.validation.RecordValidationException;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Global exception handler for the REST API.
 *
 * <p>Maps exceptions to appropriate HTTP status codes and JSON:API error responses:
 * <ul>
 *   <li>ValidationException -> 400 Bad Request</li>
 *   <li>InvalidQueryException -> 400 Bad Request</li>
 *   <li>StorageQueryException -> 400 Bad Request (client-caused SQL error, with sqlState in meta)</li>
 *   <li>UniqueConstraintViolationException -> 409 Conflict</li>
 *   <li>StorageException -> 500 Internal Server Error</li>
 *   <li>Other exceptions -> 500 Internal Server Error</li>
 * </ul>
 *
 * <p>All error responses follow the JSON:API error format with a unique request ID for tracing.
 *
 * @since 1.0.0
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles validation exceptions.
     * Returns 400 Bad Request with field-level error details in JSON:API format.
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            ValidationException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Validation failed [requestId={}]: {}", requestId, ex.getMessage());

        List<JsonApiError> errors = new ArrayList<>();
        ValidationResult result = ex.getValidationResult();
        if (result != null && !result.errors().isEmpty()) {
            for (var fieldError : result.errors()) {
                String code = fieldError.constraint() != null && !fieldError.constraint().isBlank()
                    ? fieldError.constraint() : "VALIDATION_FAILED";
                JsonApiError error = new JsonApiError(
                    "400", code, "Validation Error",
                    fieldError.message() != null ? fieldError.message() : "Invalid value");
                error.setSource(Map.of("pointer", "/data/attributes/" + fieldError.fieldName()));
                Map<String, Object> meta = new LinkedHashMap<>();
                if (fieldError.meta() != null) {
                    meta.putAll(fieldError.meta());
                }
                meta.put("requestId", requestId);
                error.setMeta(meta);
                errors.add(error);
            }
        } else {
            JsonApiError error = new JsonApiError(
                "400", "VALIDATION_FAILED", "Validation Error",
                ex.getMessage() != null ? ex.getMessage() : "Request validation failed");
            error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));
            errors.add(error);
        }

        return ResponseEntity.badRequest().body(errorBody(errors));
    }

    /**
     * Handles record validation rule exceptions (custom formula-based rules).
     * Returns 422 Unprocessable Entity per JSON:API conventions for
     * semantically invalid records that parsed correctly.
     */
    @ExceptionHandler(RecordValidationException.class)
    public ResponseEntity<Map<String, Object>> handleRecordValidationException(
            RecordValidationException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Record validation rule(s) failed [requestId={}]: {}", requestId, ex.getMessage());

        List<JsonApiError> errors = new ArrayList<>();
        for (var ruleError : ex.getErrors()) {
            String fieldName = ruleError.errorField() != null ? ruleError.errorField() : ruleError.ruleName();
            JsonApiError error = new JsonApiError(
                "422", "VALIDATION_RULE_FAILED", "Validation Error",
                ruleError.errorMessage() != null ? ruleError.errorMessage() : "Validation rule failed");
            error.setSource(Map.of("pointer", "/data/attributes/" + fieldName));
            error.setMeta(Map.of("requestId", requestId));
            errors.add(error);
        }

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody(errors));
    }

    /**
     * Handles invalid query exceptions.
     * Returns 400 Bad Request in JSON:API format.
     */
    @ExceptionHandler(InvalidQueryException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidQueryException(
            InvalidQueryException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Invalid query [requestId={}]: {}", requestId, ex.getMessage());

        List<JsonApiError> errors = new ArrayList<>();
        if (ex.getFieldName() != null) {
            JsonApiError error = new JsonApiError(
                "400", "INVALID_QUERY", "Bad Request",
                ex.getReason() != null ? ex.getReason() : "Invalid query parameter");
            error.setSource(Map.of("pointer", "/data/attributes/" + ex.getFieldName()));
            error.setMeta(Map.of("requestId", requestId));
            errors.add(error);
        } else {
            JsonApiError error = new JsonApiError(
                "400", "INVALID_QUERY", "Bad Request",
                ex.getMessage() != null ? ex.getMessage() : "Invalid query");
            error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));
            errors.add(error);
        }

        return ResponseEntity.badRequest().body(errorBody(errors));
    }

    /**
     * Handles SQL errors caused by client-supplied values (bad literal for the column
     * type, unknown column, no operator for the type) that {@code PhysicalTableStorageAdapter}
     * classified from the Postgres SQLSTATE rather than treating as a storage fault.
     * Returns 400 Bad Request with the SQLSTATE in {@code meta} — logged at WARN with no
     * stack trace, since this is a client mistake, not something to page on.
     */
    @ExceptionHandler(StorageQueryException.class)
    public ResponseEntity<Map<String, Object>> handleStorageQueryException(
            StorageQueryException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Invalid query [requestId={}] sqlState={}: {}", requestId, ex.getSqlState(), ex.getReason());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", requestId);
        meta.put("sqlState", ex.getSqlState());

        JsonApiError error = new JsonApiError(
            "400", "INVALID_QUERY", "Bad Request",
            ex.getReason() != null ? ex.getReason() : "Invalid query");
        if (ex.getFieldName() != null) {
            error.setSource(Map.of("pointer", "/data/attributes/" + ex.getFieldName()));
        } else {
            meta.put("path", request.getRequestURI());
        }
        error.setMeta(meta);

        return ResponseEntity.badRequest().body(errorBody(error));
    }

    /**
     * Handles unique constraint violation exceptions.
     * Returns 409 Conflict in JSON:API format.
     */
    @ExceptionHandler(UniqueConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleUniqueConstraintViolation(
            UniqueConstraintViolationException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Unique constraint violation [requestId={}]: {}", requestId, ex.getMessage());

        JsonApiError error = new JsonApiError(
            "409", "UNIQUE_VIOLATION", "Conflict",
            ex.getMessage() != null ? ex.getMessage() : "Unique constraint violation");
        error.setSource(Map.of("pointer", "/data/attributes/" + ex.getFieldName()));
        error.setMeta(Map.of("requestId", requestId));

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(error));
    }

    /**
     * Handles optimistic-locking conflicts (stale {@code If-Match}).
     * Returns 409 Conflict in JSON:API format so the client can reload and retry.
     */
    @ExceptionHandler(StaleWriteException.class)
    public ResponseEntity<Map<String, Object>> handleStaleWrite(
            StaleWriteException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Stale write [requestId={}]: {}", requestId, ex.getMessage());

        JsonApiError error = new JsonApiError(
            "409", "STALE_WRITE", "Conflict",
            "This record was modified since you opened it. Reload the latest version and try again.");
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(error));
    }

    /**
     * Handles deletes rejected by a restricting foreign key (SQL state 23503).
     * Returns 409 Conflict — the record is still referenced, which is a client
     * situation to resolve, not a server fault.
     */
    @ExceptionHandler(ReferencedRecordConflictException.class)
    public ResponseEntity<Map<String, Object>> handleReferencedRecordConflict(
            ReferencedRecordConflictException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Referenced-record delete conflict [requestId={}]: {}", requestId, ex.getMessage());

        JsonApiError error = new JsonApiError(
            "409", "REFERENCED_RECORD", "Conflict",
            ex.getMessage());
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(error));
    }

    /**
     * Handles a before-save hook rejecting a second "default" record within its scope
     * (e.g. a second default list view for the same collection + visibility).
     * Returns 409 Conflict with the hook-supplied code and the conflicting record's id
     * in {@code meta.existingId}.
     */
    @ExceptionHandler(DuplicateDefaultException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateDefault(
            DuplicateDefaultException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Duplicate default [requestId={}]: {}", requestId, ex.getMessage());

        JsonApiError error = new JsonApiError(
            "409", ex.getCode(), "Conflict", ex.getMessage());
        if (ex.getFieldName() != null) {
            error.setSource(Map.of("pointer", "/data/attributes/" + ex.getFieldName()));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", requestId);
        meta.put("existingId", ex.getExistingId());
        error.setMeta(meta);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(error));
    }

    /**
     * Handles storage exceptions.
     * Returns 500 Internal Server Error with generic message in JSON:API format.
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Map<String, Object>> handleStorageException(
            StorageException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.error("Storage error [requestId={}]: {}", requestId, ex.getMessage(), ex);

        JsonApiError error = new JsonApiError(
            "500", "STORAGE_ERROR", "Internal Server Error",
            "An error occurred while processing your request");
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(errorBody(error));
    }

    /**
     * Handles bean-validation errors on {@code @Valid @RequestBody} arguments.
     * Returns 400 Bad Request with one error per field violation.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Bean validation failed [requestId={}]: {}", requestId, ex.getMessage());

        List<JsonApiError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> {
            JsonApiError error = new JsonApiError(
                "400", "VALIDATION_FAILED", "Validation Error",
                fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value");
            error.setSource(Map.of("pointer", "/data/attributes/" + fe.getField()));
            error.setMeta(Map.of("requestId", requestId));
            errors.add(error);
        });
        if (errors.isEmpty()) {
            JsonApiError error = new JsonApiError(
                "400", "VALIDATION_FAILED", "Validation Error", "Request validation failed");
            error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));
            errors.add(error);
        }

        return ResponseEntity.badRequest().body(errorBody(errors));
    }

    /**
     * Handles bean-validation errors on path / query parameters annotated with
     * {@code @Validated} constraints (e.g. {@code @NotBlank String id}).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Constraint violation [requestId={}]: {}", requestId, ex.getMessage());

        List<JsonApiError> errors = new ArrayList<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            JsonApiError error = new JsonApiError(
                "400", "VALIDATION_FAILED", "Validation Error",
                v.getMessage() != null ? v.getMessage() : "Invalid value");
            String path = v.getPropertyPath() != null ? v.getPropertyPath().toString() : null;
            if (path != null && !path.isBlank()) {
                error.setSource(Map.of("parameter", path));
            }
            error.setMeta(Map.of("requestId", requestId));
            errors.add(error);
        }
        if (errors.isEmpty()) {
            JsonApiError error = new JsonApiError(
                "400", "VALIDATION_FAILED", "Validation Error", "Request validation failed");
            error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));
            errors.add(error);
        }

        return ResponseEntity.badRequest().body(errorBody(errors));
    }

    /**
     * Handles malformed JSON request bodies.
     * Returns 400 Bad Request in JSON:API format.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Malformed request body [requestId={}]: {}", requestId, ex.getMessage());

        JsonApiError error = new JsonApiError(
            "400", "INVALID_PAYLOAD", "Bad Request",
            "Request body is missing or could not be parsed as JSON");
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.badRequest().body(errorBody(error));
    }

    /**
     * Handles missing required query / form parameters.
     * Returns 400 Bad Request in JSON:API format.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Missing parameter [requestId={}]: {}", requestId, ex.getMessage());

        JsonApiError error = new JsonApiError(
            "400", "MISSING_PARAMETER", "Bad Request",
            "Required parameter '" + ex.getParameterName() + "' is missing");
        error.setSource(Map.of("parameter", ex.getParameterName()));
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.badRequest().body(errorBody(error));
    }

    /**
     * Handles path / query parameter type mismatches (e.g. non-numeric value for a {@code Long} id).
     * Returns 400 Bad Request in JSON:API format.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Parameter type mismatch [requestId={}]: {}", requestId, ex.getMessage());

        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "expected type";
        JsonApiError error = new JsonApiError(
            "400", "INVALID_PARAMETER", "Bad Request",
            "Parameter '" + ex.getName() + "' could not be converted to " + expected);
        error.setSource(Map.of("parameter", ex.getName()));
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.badRequest().body(errorBody(error));
    }

    /**
     * Handles missing routes (no controller for the request path).
     * Spring 6.1+ throws {@link NoResourceFoundException}; earlier versions threw
     * {@link NoHandlerFoundException}. Both are mapped to a JSON:API 404 envelope.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(
            Exception ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        String path = request.getRequestURI();
        logger.warn("Route not found [requestId={}]: {} {}", requestId, request.getMethod(), path);

        JsonApiError error = new JsonApiError(
            "404", "NOT_FOUND", "Not Found",
            "No handler for " + request.getMethod() + " " + path);
        error.setMeta(Map.of("requestId", requestId, "path", path));

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(error));
    }

    /**
     * Handles unsupported HTTP methods on an existing route.
     * Returns 405 Method Not Allowed in JSON:API format.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Method not allowed [requestId={}]: {}", requestId, ex.getMessage());

        JsonApiError error = new JsonApiError(
            "405", "METHOD_NOT_ALLOWED", "Method Not Allowed",
            "Method " + ex.getMethod() + " is not supported for this endpoint");
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(errorBody(error));
    }

    /**
     * Handles requests with unsupported {@code Content-Type}.
     * Returns 415 Unsupported Media Type in JSON:API format.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.warn("Unsupported media type [requestId={}]: {}", requestId, ex.getMessage());

        JsonApiError error = new JsonApiError(
            "415", "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type",
            ex.getContentType() != null
                ? "Content-Type '" + ex.getContentType() + "' is not supported"
                : "Request Content-Type is not supported");
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(errorBody(error));
    }

    /**
     * Handles Spring's {@link ResponseStatusException} — used by controllers that throw
     * an explicit HTTP status. Preserves the status and reason and wraps in JSON:API format.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        logger.warn("ResponseStatusException [requestId={}, status={}]: {}",
                requestId, status, ex.getReason());

        String code = status.name();
        String detail = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        JsonApiError error = new JsonApiError(
            String.valueOf(status.value()), code, status.getReasonPhrase(), detail);
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.status(status).body(errorBody(error));
    }

    /**
     * Handles all other exceptions.
     * Returns 500 Internal Server Error with generic message in JSON:API format.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, HttpServletRequest request) {

        String requestId = generateRequestId();
        logger.error("Unexpected error [requestId={}]: {}", requestId, ex.getMessage(), ex);

        JsonApiError error = new JsonApiError(
            "500", "INTERNAL_ERROR", "Internal Server Error", "An unexpected error occurred");
        error.setMeta(Map.of("requestId", requestId, "path", request.getRequestURI()));

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(errorBody(error));
    }

    /**
     * Generates a unique request ID for tracing.
     */
    private String generateRequestId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Builds the JSON:API error envelope from plain maps ({@link JsonApiError#toMap()}).
     * Maps serialize identically under every converter configuration; serializing the
     * bean itself has been observed to produce {@code {"errors":[{}]}} on the deployed
     * worker, hiding the failure reason from API clients.
     */
    private static Map<String, Object> errorBody(List<JsonApiError> errors) {
        List<Map<String, Object>> mapped = new ArrayList<>(errors.size());
        for (JsonApiError error : errors) {
            mapped.add(error.toMap());
        }
        return Map.of("errors", mapped);
    }

    private static Map<String, Object> errorBody(JsonApiError error) {
        return errorBody(List.of(error));
    }
}
