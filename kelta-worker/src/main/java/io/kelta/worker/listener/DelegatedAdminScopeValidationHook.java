package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.repository.DelegatedAdminScopeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates {@code delegated-admin-scopes} writes — the "no delegating admin-of-admins" gate.
 *
 * <p>Rejects a scope whose {@code manageableProfileIds} contains a profile that grants any
 * privileged permission ({@code PrivilegedPermissions.SET}); validates that every referenced user
 * and profile exists in the tenant; and enforces shape (arrays of non-blank id strings,
 * deduplicated, size-capped). {@code DelegatedAdminService} re-checks the privileged filter at
 * request time, so this hook is the first of two gates, not the only one.
 */
@Component
public class DelegatedAdminScopeValidationHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(DelegatedAdminScopeValidationHook.class);

    static final String COLLECTION = "delegated-admin-scopes";
    static final int MAX_USERS = 500;
    static final int MAX_PROFILES = 100;
    private static final int MAX_ID_LENGTH = 64;

    private final DelegatedAdminScopeRepository scopeRepository;
    private final ObjectMapper objectMapper;

    public DelegatedAdminScopeValidationHook(DelegatedAdminScopeRepository scopeRepository,
                                             ObjectMapper objectMapper) {
        this.scopeRepository = scopeRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validate(record, tenantId);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        return validate(record, tenantId);
    }

    private BeforeSaveResult validate(Map<String, Object> record, String tenantId) {
        // delegatedUserIds
        if (record.containsKey("delegatedUserIds")) {
            ParseResult users = parseIds(record.get("delegatedUserIds"), MAX_USERS);
            if (users.error != null) {
                return BeforeSaveResult.error("delegatedUserIds", users.error);
            }
            Set<String> missing = missing(users.ids, scopeRepository.findExistingUserIds(users.ids, tenantId));
            if (!missing.isEmpty()) {
                return BeforeSaveResult.error("delegatedUserIds", "Unknown user(s): " + missing);
            }
            record.put("delegatedUserIds", List.copyOf(users.ids));
        }

        // manageableProfileIds
        if (record.containsKey("manageableProfileIds")) {
            ParseResult profiles = parseIds(record.get("manageableProfileIds"), MAX_PROFILES);
            if (profiles.error != null) {
                return BeforeSaveResult.error("manageableProfileIds", profiles.error);
            }
            Set<String> missing = missing(profiles.ids,
                    scopeRepository.findExistingProfileIds(profiles.ids, tenantId));
            if (!missing.isEmpty()) {
                return BeforeSaveResult.error("manageableProfileIds", "Unknown profile(s): " + missing);
            }
            Set<String> privileged = scopeRepository.findPrivilegedProfileIds(profiles.ids);
            if (!privileged.isEmpty()) {
                log.warn("Rejected delegated-admin scope in tenant {}: privileged profiles {}", tenantId, privileged);
                return BeforeSaveResult.error("manageableProfileIds",
                        "Profiles granting administrative permissions cannot be delegated: " + privileged);
            }
            record.put("manageableProfileIds", List.copyOf(profiles.ids));
        }

        return BeforeSaveResult.ok();
    }

    private static Set<String> missing(Set<String> requested, Set<String> existing) {
        Set<String> missing = new LinkedHashSet<>(requested);
        missing.removeAll(existing);
        return missing;
    }

    /**
     * Accepts a JSON string or a {@code List}; every element must be a non-blank id string
     * without whitespace, at most {@value #MAX_ID_LENGTH} chars. Dedupes; enforces the cap.
     */
    private ParseResult parseIds(Object value, int cap) {
        List<?> raw;
        if (value == null) {
            return ParseResult.ok(Set.of());
        } else if (value instanceof List<?> list) {
            raw = list;
        } else if (value instanceof String s) {
            try {
                raw = objectMapper.readValue(s, List.class);
            } catch (Exception e) {
                return ParseResult.fail("Must be a JSON array of id strings");
            }
        } else {
            return ParseResult.fail("Must be an array of id strings");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object element : raw) {
            if (!(element instanceof String id) || id.isBlank()
                    || id.length() > MAX_ID_LENGTH || id.chars().anyMatch(Character::isWhitespace)) {
                return ParseResult.fail("Must contain only non-blank id strings (max "
                        + MAX_ID_LENGTH + " chars)");
            }
            ids.add(id);
        }
        if (ids.size() > cap) {
            return ParseResult.fail("At most " + cap + " entries allowed");
        }
        return ParseResult.ok(ids);
    }

    private record ParseResult(Set<String> ids, String error) {
        static ParseResult ok(Set<String> ids) {
            return new ParseResult(ids, null);
        }

        static ParseResult fail(String error) {
            return new ParseResult(Set.of(), error);
        }
    }
}
