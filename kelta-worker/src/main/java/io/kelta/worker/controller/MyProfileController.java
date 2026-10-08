package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.validation.FieldError;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import io.kelta.worker.service.SelfProfileWriteContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Member self-profile: any authenticated caller (INTERNAL or PORTAL) reads and updates a fixed
 * allow-list of fields on <em>their own</em> {@code users} row, without {@code MANAGE_USERS}.
 *
 * <p>The caller is {@link CallerContext#current()} only — never an id from the path or body — so
 * there is no way to name another user. No bound caller (internal tier, machine identity) or no
 * row reads as 404. {@code /api/me/**} is a static gateway route with only the blanket
 * {@code API_ACCESS} check; this controller is the whole authorization.
 *
 * <p>The allow-list is code, intentionally not configurable: widening it is a reviewed change.
 * Any other attribute ({@code email}, {@code status}, {@code profileId}, {@code userType},
 * {@code managerId}, {@code mfaEnabled}, {@code settings}, …) is rejected with
 * {@code FIELD_NOT_EDITABLE}. Validated writes run inside {@link SelfProfileWriteContext}, which
 * {@code IdentityCollectionGuardHook} honours only for this caller's id and these fields.
 */
@RestController
@RequestMapping("/api/me/profile")
public class MyProfileController {

    private static final Logger log = LoggerFactory.getLogger(MyProfileController.class);

    static final Set<String> EDITABLE_FIELDS = Set.of("firstName", "lastName", "locale", "timezone");

    private static final List<String> READ_FIELDS =
            List.of("email", "firstName", "lastName", "locale", "timezone", "userType");

    static final int MAX_NAME_LENGTH = 100;

    /** Language with an optional region ({@code en}, {@code pt_BR}, {@code es-419}) — fits the 10-char column. */
    private static final Pattern LOCALE_PATTERN =
            Pattern.compile("^([a-zA-Z]{2,3})(?:[_-]([a-zA-Z]{2}|[0-9]{3}))?$");

    private static final Set<String> ISO_LANGUAGES = Set.of(Locale.getISOLanguages());
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;

    public MyProfileController(QueryEngine queryEngine, CollectionRegistry collectionRegistry) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        String callerId = requireCallerId();
        Map<String, Object> row = queryEngine.getById(usersDefinition(), callerId)
                .orElseThrow(MyProfileController::notFound);
        return ResponseEntity.ok(document(callerId, row));
    }

    @PatchMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) Map<String, Object> body) {
        String callerId = requireCallerId();
        Map<String, Object> attrs = validate(attributes(body));
        if (attrs.isEmpty()) {
            return get();
        }
        Map<String, Object> updated = SelfProfileWriteContext.callAuthorized(callerId, EDITABLE_FIELDS,
                        () -> queryEngine.update(usersDefinition(), callerId, attrs))
                .orElseThrow(MyProfileController::notFound);
        log.info("User {} updated their own profile: {}", callerId, attrs.keySet());
        return ResponseEntity.ok(document(callerId, updated));
    }

    // ------------------------------------------------------------- Helpers

    /**
     * Rejects every non-editable attribute, then validates and normalizes the editable ones.
     * All problems are reported together, each with its {@code /data/attributes/<field>} pointer.
     */
    private Map<String, Object> validate(Map<String, Object> attrs) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (!EDITABLE_FIELDS.contains(field)) {
                errors.add(new FieldError(field,
                        "Field '" + field + "' cannot be changed through your profile", "FIELD_NOT_EDITABLE"));
                continue;
            }
            if (value != null && !(value instanceof String)) {
                errors.add(invalid(field, field + " must be a string"));
                continue;
            }
            String text = value == null ? null : ((String) value).trim();
            switch (field) {
                case "firstName", "lastName" -> {
                    if (text != null && text.length() > MAX_NAME_LENGTH) {
                        errors.add(invalid(field, field + " must be at most " + MAX_NAME_LENGTH + " characters"));
                    } else {
                        accepted.put(field, text == null || text.isEmpty() ? null : text);
                    }
                }
                case "locale" -> {
                    if (!isSupportedLocale(text)) {
                        errors.add(invalid(field, "locale must be a language tag such as 'en' or 'pt_BR'"));
                    } else {
                        accepted.put(field, text);
                    }
                }
                case "timezone" -> {
                    if (text == null || !ZoneId.getAvailableZoneIds().contains(text)) {
                        errors.add(invalid(field, "timezone must be an IANA time zone such as 'Europe/Lisbon'"));
                    } else {
                        accepted.put(field, text);
                    }
                }
                default -> throw new IllegalStateException("Unhandled editable field " + field);
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(ValidationResult.failure(errors));
        }
        return accepted;
    }

    static boolean isSupportedLocale(String tag) {
        if (tag == null) {
            return false;
        }
        Matcher m = LOCALE_PATTERN.matcher(tag);
        if (!m.matches() || !ISO_LANGUAGES.contains(m.group(1).toLowerCase(Locale.ROOT))) {
            return false;
        }
        String region = m.group(2);
        return region == null || Character.isDigit(region.charAt(0))
                || ISO_COUNTRIES.contains(region.toUpperCase(Locale.ROOT));
    }

    private static FieldError invalid(String field, String message) {
        return new FieldError(field, message, "VALIDATION_FAILED");
    }

    private static Map<String, Object> document(String callerId, Map<String, Object> row) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (String field : READ_FIELDS) {
            attrs.put(field, row.get(field));
        }
        return JsonApiResponseBuilder.single("users", callerId, attrs);
    }

    private static String requireCallerId() {
        return CallerContext.current()
                .map(CallerContext::userId)
                .filter(id -> !id.isBlank())
                .orElseThrow(MyProfileController::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found");
    }

    private CollectionDefinition usersDefinition() {
        CollectionDefinition definition = collectionRegistry.get("users");
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "users collection not registered");
        }
        return definition;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        Object data = body.get("data");
        if (data instanceof Map<?, ?> dataMap && dataMap.get("attributes") instanceof Map<?, ?> attrs) {
            return (Map<String, Object>) attrs;
        }
        return body;
    }
}
