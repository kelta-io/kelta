package io.kelta.runtime.router;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kelta.runtime.query.InvalidQueryException;
import io.kelta.runtime.storage.StorageException;
import io.kelta.runtime.storage.StorageQueryException;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import io.kelta.runtime.validation.RecordValidationException;
import io.kelta.runtime.validation.ValidationError;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the JSON:API 4xx error envelope: every response body has a non-empty
 * {@code errors[]} array, and each error object carries {@code status}, {@code code},
 * and {@code detail} so clients can distinguish failure classes without trial-and-error.
 *
 * <p>Error objects are plain maps ({@code JsonApiError.toMap()}), asserted here as maps
 * — the same shape that goes over the wire. Bean-typed bodies serialized to
 * {@code {"errors":[{}]}} on the deployed worker, so the envelope's serialized form is
 * itself under test (see {@link #errorEnvelope_serializesWithAllMembers()}).
 */
@SuppressWarnings("unchecked")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;
    private ch.qos.logback.classic.Logger handlerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest("POST", "/api/widgets");

        handlerLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
    }

    private static List<Map<String, Object>> errors(ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("errors");
    }

    private static Map<String, Object> firstError(ResponseEntity<Map<String, Object>> response) {
        return errors(response).get(0);
    }

    private static String str(Map<String, Object> error, String member) {
        return (String) error.get(member);
    }

    private static Map<String, Object> source(Map<String, Object> error) {
        return (Map<String, Object>) error.get("source");
    }

    private static Map<String, Object> meta(Map<String, Object> error) {
        return (Map<String, Object>) error.get("meta");
    }

    @Test
    void methodArgumentNotValid_emitsFieldLevelErrors() throws NoSuchMethodException {
        Method m = Sample.class.getDeclaredMethod("create", String.class);
        MethodParameter mp = new MethodParameter(m, 0);

        BindingResult bindingResult = new MapBindingResult(Map.of(), "widget");
        bindingResult.addError(new FieldError("widget", "name", null, false,
                new String[]{"NotBlank"}, null, "name must not be blank"));
        bindingResult.addError(new FieldError("widget", "size", null, false,
                new String[]{"Positive"}, null, "size must be > 0"));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(mp, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleMethodArgumentNotValid(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> errors = errors(response);
        assertThat(errors).hasSize(2);
        for (Map<String, Object> e : errors) {
            assertThat(str(e, "status")).isEqualTo("400");
            assertThat(str(e, "code")).isEqualTo("VALIDATION_FAILED");
            assertThat(str(e, "detail")).isNotBlank();
            assertThat(source(e)).containsKey("pointer");
        }
        assertThat(source(errors.get(0)).get("pointer")).isEqualTo("/data/attributes/name");
        assertThat(source(errors.get(1)).get("pointer")).isEqualTo("/data/attributes/size");
    }

    @Test
    void constraintViolation_emitsParameterPointers() {
        ConstraintViolation<Object> v = mock(ConstraintViolation.class);
        when(v.getMessage()).thenReturn("must be greater than 0");
        jakarta.validation.Path path = mock(jakarta.validation.Path.class);
        when(path.toString()).thenReturn("limit");
        when(v.getPropertyPath()).thenReturn(path);

        Set<ConstraintViolation<?>> violations = new HashSet<>();
        violations.add(v);
        ConstraintViolationException ex = new ConstraintViolationException("bad params", violations);

        ResponseEntity<Map<String, Object>> response = handler.handleConstraintViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> errors = errors(response);
        assertThat(errors).hasSize(1);
        Map<String, Object> e = errors.get(0);
        assertThat(str(e, "status")).isEqualTo("400");
        assertThat(str(e, "code")).isEqualTo("VALIDATION_FAILED");
        assertThat(str(e, "detail")).isEqualTo("must be greater than 0");
        assertThat(source(e)).containsEntry("parameter", "limit");
    }

    @Test
    void httpMessageNotReadable_emits400WithInvalidPayload() {
        HttpInputMessage input = new HttpInputMessage() {
            @Override public InputStream getBody() { return InputStream.nullInputStream(); }
            @Override public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("malformed JSON", input);

        ResponseEntity<Map<String, Object>> response = handler.handleHttpMessageNotReadable(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> errors = errors(response);
        assertThat(errors).hasSize(1);
        Map<String, Object> e = errors.get(0);
        assertThat(str(e, "status")).isEqualTo("400");
        assertThat(str(e, "code")).isEqualTo("INVALID_PAYLOAD");
        assertThat(str(e, "title")).isEqualTo("Bad Request");
        assertThat(str(e, "detail")).contains("could not be parsed");
        assertThat(meta(e)).containsEntry("path", "/api/widgets");
    }

    @Test
    void multipartException_emits400WithInvalidPayload() {
        MultipartException ex = new MultipartException("Current request is not a multipart request");

        ResponseEntity<Map<String, Object>> response = handler.handleMultipartException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("400");
        assertThat(str(e, "code")).isEqualTo("INVALID_PAYLOAD");
        assertThat(str(e, "title")).isEqualTo("Bad Request");
        assertThat(str(e, "detail")).isEqualTo("multipart/form-data with a file part is required");
        assertThat(meta(e)).containsEntry("path", "/api/widgets");
    }

    @Test
    void missingParameter_emits400WithSource() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("filter", "String");

        ResponseEntity<Map<String, Object>> response = handler.handleMissingParameter(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("400");
        assertThat(str(e, "code")).isEqualTo("MISSING_PARAMETER");
        assertThat(str(e, "detail")).isEqualTo("Required parameter 'filter' is missing");
        assertThat(source(e)).containsEntry("parameter", "filter");
    }

    @Test
    void typeMismatch_emits400WithSource() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", null, new IllegalArgumentException("bad number"));

        ResponseEntity<Map<String, Object>> response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("400");
        assertThat(str(e, "code")).isEqualTo("INVALID_PARAMETER");
        assertThat(str(e, "detail")).contains("'id'").contains("Long");
        assertThat(source(e)).containsEntry("parameter", "id");
    }

    @Test
    void noResourceFound_emits404() {
        NoResourceFoundException ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/missing", "static");
        request.setRequestURI("/api/missing");
        request.setMethod("GET");

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("404");
        assertThat(str(e, "code")).isEqualTo("NOT_FOUND");
        assertThat(str(e, "title")).isEqualTo("Not Found");
        assertThat(str(e, "detail")).contains("GET").contains("/api/missing");
    }

    @Test
    void noHandlerFound_emits404() {
        NoHandlerFoundException ex = new NoHandlerFoundException(
                "PUT", "/api/missing", new org.springframework.http.HttpHeaders());
        request.setRequestURI("/api/missing");
        request.setMethod("PUT");

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("404");
        assertThat(str(e, "code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void methodNotAllowed_emits405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

        ResponseEntity<Map<String, Object>> response = handler.handleMethodNotAllowed(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("405");
        assertThat(str(e, "code")).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(str(e, "detail")).contains("DELETE");
    }

    @Test
    void unsupportedMediaType_emits415() {
        HttpMediaTypeNotSupportedException ex =
                new HttpMediaTypeNotSupportedException(org.springframework.http.MediaType.TEXT_PLAIN,
                        List.of(org.springframework.http.MediaType.APPLICATION_JSON));

        ResponseEntity<Map<String, Object>> response = handler.handleUnsupportedMediaType(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("415");
        assertThat(str(e, "code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(str(e, "detail")).contains("text/plain");
    }

    @Test
    void responseStatus_preservesStatusAndReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.CONFLICT, "Widget already exists");

        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatus(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("409");
        assertThat(str(e, "code")).isEqualTo("CONFLICT");
        assertThat(str(e, "detail")).isEqualTo("Widget already exists");
    }

    @Test
    void validationException_emitsFieldLevelErrorsWithCodeAndPointer() {
        ValidationResult result = ValidationResult.failure(List.of(
                new io.kelta.runtime.validation.FieldError("email", "must be a valid email", "PATTERN"),
                io.kelta.runtime.validation.FieldError.nullable("name")));

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidationException(new ValidationException(result), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> errors = errors(response);
        assertThat(errors).hasSize(2);
        for (Map<String, Object> e : errors) {
            assertThat(str(e, "status")).isEqualTo("400");
            assertThat(str(e, "code")).isNotBlank();
            assertThat(str(e, "detail")).isNotBlank();
            assertThat(source(e)).containsKey("pointer");
        }
        assertThat(source(errors.get(0)).get("pointer")).isEqualTo("/data/attributes/email");
        assertThat(str(errors.get(0), "code")).isEqualTo("PATTERN");
        assertThat(str(errors.get(1), "code")).isEqualTo("nullable");
    }

    @Test
    void validationException_referenceError_mergesFieldValueAndTargetCollectionIntoMeta() {
        ValidationResult result = ValidationResult.failure(List.of(
                io.kelta.runtime.validation.FieldError.reference("sectionId", "layout-sections", "page-layout-1")));

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidationException(new ValidationException(result), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "code")).isEqualTo("reference");
        assertThat(str(e, "detail")).contains("page-layout-1", "layout-sections", "sectionId");
        assertThat(source(e)).containsEntry("pointer", "/data/attributes/sectionId");
        Map<String, Object> meta = meta(e);
        assertThat(meta).containsEntry("field", "sectionId");
        assertThat(meta).containsEntry("value", "page-layout-1");
        assertThat(meta).containsEntry("targetCollection", "layout-sections");
        assertThat(meta).containsKey("requestId");
        assertThat(meta).hasSize(4);
    }

    @Test
    void validationException_referenceTargetMissing_omitsValueFromMeta() {
        ValidationResult result = ValidationResult.failure(List.of(
                io.kelta.runtime.validation.FieldError.referenceTargetMissing("sectionId", "no-such-collection")));

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidationException(new ValidationException(result), request);

        Map<String, Object> e = firstError(response);
        Map<String, Object> meta = meta(e);
        assertThat(meta).containsEntry("field", "sectionId");
        assertThat(meta).containsEntry("targetCollection", "no-such-collection");
        assertThat(meta).containsKey("requestId");
        assertThat(meta).doesNotContainKey("value");
    }

    @Test
    void recordValidationException_emits422WithRuleFailedCode() {
        RecordValidationException ex = new RecordValidationException(List.of(
                new ValidationError("title_year_range",
                        "Year must be between 1888 and 2031", "year")));

        ResponseEntity<Map<String, Object>> response =
                handler.handleRecordValidationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        List<Map<String, Object>> errors = errors(response);
        assertThat(errors).hasSize(1);
        Map<String, Object> e = errors.get(0);
        assertThat(str(e, "status")).isEqualTo("422");
        assertThat(str(e, "code")).isEqualTo("VALIDATION_RULE_FAILED");
        assertThat(str(e, "detail")).isEqualTo("Year must be between 1888 and 2031");
        assertThat(source(e)).containsEntry("pointer", "/data/attributes/year");
    }

    @Test
    void recordValidationException_withoutErrorField_pointsAtRuleName() {
        RecordValidationException ex = new RecordValidationException(List.of(
                new ValidationError("cross_field_rule", "Inconsistent state", null)));

        ResponseEntity<Map<String, Object>> response =
                handler.handleRecordValidationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Map<String, Object> e = firstError(response);
        assertThat(source(e)).containsEntry("pointer", "/data/attributes/cross_field_rule");
    }

    @Test
    void invalidQueryException_withField_includesPointer() {
        InvalidQueryException ex = new InvalidQueryException("sort", "unknown sort field");

        ResponseEntity<Map<String, Object>> response =
                handler.handleInvalidQueryException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("400");
        assertThat(str(e, "code")).isEqualTo("INVALID_QUERY");
        assertThat(str(e, "detail")).isEqualTo("unknown sort field");
        assertThat(source(e)).containsEntry("pointer", "/data/attributes/sort");
    }

    @Test
    void storageQueryException_withField_emits400WithSqlStateAndPointer() {
        StorageQueryException ex = new StorageQueryException(
                "id", "invalid input syntax for type uuid: \"not-a-uuid\"", "22P02",
                new RuntimeException("driver cause"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleStorageQueryException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("400");
        assertThat(str(e, "code")).isEqualTo("INVALID_QUERY");
        assertThat(str(e, "detail")).contains("not-a-uuid");
        assertThat(source(e)).containsEntry("pointer", "/data/attributes/id");
        assertThat(meta(e)).containsEntry("sqlState", "22P02");
    }

    @Test
    void storageQueryException_withoutField_emits400WithSqlStateAndPath() {
        StorageQueryException ex = new StorageQueryException(
                "operator does not exist: character varying >= timestamp", "42883",
                new RuntimeException("driver cause"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleStorageQueryException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> e = firstError(response);
        assertThat(meta(e)).containsEntry("sqlState", "42883");
        assertThat(meta(e)).containsEntry("path", "/api/widgets");
        assertThat(source(e)).isNull();
    }

    @Test
    void storageQueryException_logsExactlyOneWarnLineWithNoStackTrace() {
        StorageQueryException ex = new StorageQueryException(
                "id", "invalid input syntax for type uuid: \"not-a-uuid\"", "22P02",
                new RuntimeException("driver cause"));

        handler.handleStorageQueryException(ex, request);

        assertThat(logAppender.list).hasSize(1);
        ILoggingEvent event = logAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void storageException_emits500WithGenericDetailAndSqlErrorStaysHidden() {
        StorageException ex = new StorageException("Failed to query collection: widgets",
                new RuntimeException("Connection refused"));

        ResponseEntity<Map<String, Object>> response =
                handler.handleStorageException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("500");
        assertThat(str(e, "code")).isEqualTo("STORAGE_ERROR");
        assertThat(str(e, "detail")).doesNotContain("Connection refused");
    }

    @Test
    void uniqueConstraintViolation_emits409WithPointer() {
        UniqueConstraintViolationException ex =
                new UniqueConstraintViolationException("widgets", "name", "duplicate");

        ResponseEntity<Map<String, Object>> response =
                handler.handleUniqueConstraintViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("409");
        assertThat(str(e, "code")).isEqualTo("UNIQUE_VIOLATION");
        assertThat(str(e, "detail")).isNotBlank();
        assertThat(source(e)).containsEntry("pointer", "/data/attributes/name");
    }

    @Test
    void hookConflict_emits409WithCodeAndMeta() {
        io.kelta.runtime.workflow.HookConflictException ex = new io.kelta.runtime.workflow.HookConflictException(
                "DEFAULT_VIEW_EXISTS", "isDefault",
                "A default list view already exists for this collection and visibility",
                Map.of("existingId", "lv-existing"));

        ResponseEntity<Map<String, Object>> response = handler.handleHookConflict(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("409");
        assertThat(str(e, "code")).isEqualTo("DEFAULT_VIEW_EXISTS");
        assertThat(source(e)).containsEntry("pointer", "/data/attributes/isDefault");
        assertThat(meta(e)).containsEntry("existingId", "lv-existing");
    }

    @Test
    void genericException_emits500WithGenericDetail() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleGenericException(new RuntimeException("secret stack trace"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> e = firstError(response);
        assertThat(str(e, "status")).isEqualTo("500");
        assertThat(str(e, "code")).isEqualTo("INTERNAL_ERROR");
        // detail must not leak the original exception message
        assertThat(str(e, "detail")).doesNotContain("secret stack trace");
    }

    @Test
    void errorEnvelope_serializesWithAllMembers() {
        // Regression: the deployed worker returned {"errors":[{}]} — the bean
        // members were dropped at serialization time. The envelope is maps now,
        // so any mapper must produce the full JSON:API error object.
        ValidationResult result = ValidationResult.failure(List.of(
                new io.kelta.runtime.validation.FieldError("filters", "Invalid type, expected JSON", "type")));

        ResponseEntity<Map<String, Object>> response =
                handler.handleValidationException(new ValidationException(result), request);

        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(response.getBody());
        assertThat(json)
                .contains("\"status\":\"400\"")
                .contains("\"code\":\"type\"")
                .contains("\"detail\":\"Invalid type, expected JSON\"")
                .contains("\"pointer\":\"/data/attributes/filters\"")
                .doesNotContain("{}");
    }

    static class Sample {
        @SuppressWarnings("unused")
        public void create(String body) {}
    }
}
