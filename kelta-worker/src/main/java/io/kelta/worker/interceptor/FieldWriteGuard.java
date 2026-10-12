package io.kelta.worker.interceptor;

import io.kelta.worker.service.CerbosAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Field-level write rules for one record write: removes from the submitted values every field
 * the caller's Cerbos {@code field} policy denies {@code write} on (HIDDEN, READ_ONLY and MASKED
 * visibility), so the stored record keeps its current value on an update and gets the field's
 * default (or null) on a create.
 *
 * <p>Shared by {@link CerbosFieldWriteSecurityAdvice} (HTTP create/update bodies) and the atomic
 * operations endpoint, so every HTTP write path applies the same rule. Fields are stripped, not
 * rejected: a client that echoes a whole record back keeps working.
 *
 * <p>Fail-closed: if Cerbos cannot answer, every non-system field is removed.
 */
@Component
public class FieldWriteGuard {

    private static final Logger log = LoggerFactory.getLogger(FieldWriteGuard.class);
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    /** Audit fields the platform stamps itself; never subject to field rules. */
    static final Set<String> SYSTEM_FIELDS = Set.of("createdAt", "updatedAt", "createdBy", "updatedBy");

    private final CerbosAuthorizationService authzService;

    public FieldWriteGuard(CerbosAuthorizationService authzService) {
        this.authzService = authzService;
    }

    /**
     * True when {@code collectionName} is a platform metadata collection exempt from field-level
     * rules — the same reserved set, matched the same way, as the request-path check the field
     * advices use ({@code /api/<collectionName>}).
     */
    public static boolean isMetadataCollection(String collectionName) {
        return collectionName != null && ApiPaths.isMetadataPath("/api/" + collectionName);
    }

    /**
     * Removes the fields the caller may not write from {@code attributes} and, when given, from
     * {@code relationships} (a JSON:API {@code relationships} block, whose keys are field names
     * the write turns into values). Both maps must be mutable.
     *
     * @return the removed field names, in submission order (empty when nothing was removed)
     */
    public List<String> stripUnwritable(Map<String, Object> attributes, Map<String, Object> relationships,
                                        String email, String profileId, String tenantId,
                                        String collectionName) {
        Set<String> submitted = new LinkedHashSet<>();
        if (attributes != null) {
            submitted.addAll(attributes.keySet());
        }
        if (relationships != null) {
            submitted.addAll(relationships.keySet());
        }
        List<String> fieldsToCheck = submitted.stream()
                .filter(f -> f != null && !SYSTEM_FIELDS.contains(f))
                .toList();
        if (fieldsToCheck.isEmpty()) {
            return List.of();
        }

        Set<String> allowed;
        try {
            allowed = new HashSet<>(authzService.batchCheckFieldAccess(
                    email, profileId, tenantId, collectionName, fieldsToCheck, "write"));
        } catch (Exception e) {
            log.error("Cerbos unreachable for write field check — fail-closed, stripping all non-system fields", e);
            securityLog.warn("security_event=FIELD_WRITE_DENIED_CERBOS_FAILURE user={} collection={} fields={}",
                    email, collectionName, fieldsToCheck);
            remove(fieldsToCheck, attributes, relationships);
            return fieldsToCheck;
        }

        List<String> denied = new ArrayList<>();
        for (String field : fieldsToCheck) {
            if (!allowed.contains(field)) {
                denied.add(field);
            }
        }
        if (denied.isEmpty()) {
            return List.of();
        }
        log.debug("Stripping {} write-denied fields from {} for user={}", denied.size(), collectionName, email);
        securityLog.info("security_event=FIELD_WRITE_DENIED user={} collection={} fields={}",
                email, collectionName, denied);
        remove(denied, attributes, relationships);
        return List.copyOf(denied);
    }

    private static void remove(List<String> fields, Map<String, Object> attributes,
                               Map<String, Object> relationships) {
        for (String field : fields) {
            if (attributes != null) {
                attributes.remove(field);
            }
            if (relationships != null) {
                relationships.remove(field);
            }
        }
    }
}
