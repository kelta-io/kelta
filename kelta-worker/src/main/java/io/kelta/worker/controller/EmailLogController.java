package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.interceptor.CerbosRecordAuthorizationAdvice;
import io.kelta.worker.interceptor.DerivedDataController;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.EmailRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The email log of one record — recipients, subjects and delivery status of mail sent about it —
 * consumed by the record activity timeline.
 *
 * <p>The log is derived from the record ({@link DerivedDataController}): before reading it, the
 * record is loaded from the named collection through the {@link QueryEngine} (tenant and owner
 * scope apply) and authorized with {@link CerbosRecordAuthorizationAdvice#isRecordReadable}. A
 * record that does not exist in that collection, or that the caller may not read, is a 404 — so
 * naming a readable collection with another collection's record id reveals nothing. The caller
 * also needs {@code MANAGE_EMAIL_TEMPLATES}, the gate the log had before (it is a static gateway
 * route with only the blanket {@code API_ACCESS} check).
 *
 * <p>The collection is a request parameter because {@code email_log} stores only the source
 * record's id. The timeline treats any refusal as "no email entries".
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/email")
public class EmailLogController implements DerivedDataController {

    private static final String PERMISSION = "MANAGE_EMAIL_TEMPLATES";
    private static final int MAX_LIMIT = 100;

    private final EmailRepository emailRepository;
    private final CollectionRegistry collectionRegistry;
    private final QueryEngine queryEngine;
    private final CerbosRecordAuthorizationAdvice recordAuthorization;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public EmailLogController(EmailRepository emailRepository,
                              CollectionRegistry collectionRegistry,
                              QueryEngine queryEngine,
                              CerbosRecordAuthorizationAdvice recordAuthorization,
                              CerbosPermissionResolver permissionResolver,
                              BootstrapRepository bootstrapRepository) {
        this.emailRepository = emailRepository;
        this.collectionRegistry = collectionRegistry;
        this.queryEngine = queryEngine;
        this.recordAuthorization = recordAuthorization;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> listLogsByRecord(
            @RequestParam String collection,
            @RequestParam String recordId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        requirePermission(request);

        CollectionDefinition definition = collectionRegistry.get(collection);
        if (definition == null) {
            return ResponseEntity.notFound().build();
        }
        Optional<Map<String, Object>> record = queryEngine.getById(definition, recordId);
        if (record.isEmpty() || !recordAuthorization.isRecordReadable(request, collection, record.get())) {
            return ResponseEntity.notFound().build();
        }

        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<Map<String, Object>> logs = emailRepository.findLogsBySourceId(recordId, effectiveLimit);

        List<Map<String, Object>> records = logs.stream()
                .map(row -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", row.get("id"));
                    entry.put("recipientEmail", row.get("recipient_email"));
                    entry.put("subject", row.get("subject"));
                    entry.put("status", row.get("status"));
                    entry.put("sentAt", row.get("sent_at") != null ? row.get("sent_at").toString() : null);
                    entry.put("createdAt", row.get("created_at") != null ? row.get("created_at").toString() : null);
                    return entry;
                })
                .toList();

        return ResponseEntity.ok(JsonApiResponseBuilder.collection("email-logs", records));
    }

    private void requirePermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, PERMISSION + " permission required");
        }
    }
}
