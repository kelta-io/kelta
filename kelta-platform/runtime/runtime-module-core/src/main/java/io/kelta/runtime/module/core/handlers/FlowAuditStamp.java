package io.kelta.runtime.module.core.handlers;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stamps {@code createdBy}/{@code updatedBy} audit fields on record data
 * written by flow action handlers.
 *
 * <p>The HTTP router stamps these from the gateway's {@code X-User-Id} header,
 * but flow writes bypass the router — before this, every record created or
 * updated by a flow carried {@code null} audit users. The flow start sites
 * resolve an actor (initiating user → flow {@code runAsUserId} → flow owner)
 * into {@code ActionContext.userId}; this helper applies it.
 *
 * <p>The audit fields are server-controlled: a {@code createdBy} /
 * {@code updatedBy} field mapping in the step's config is dropped, never
 * stored. {@code createdBy} is the default owner field of owner-scoped
 * collections, so a mapping that won would let any flow author create a record
 * owned by — or attributed to — any user.
 *
 * <p>Only UUID-shaped actors are stamped: legacy provenance markers (the
 * webhook path historically passed the literal {@code "webhook"}) must never
 * reach a UUID column. With no stampable actor the fields stay unset.
 */
final class FlowAuditStamp {

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private FlowAuditStamp() {
    }

    /** True when the actor can be stored in the audit UUID columns. */
    static boolean isStampable(String userId) {
        return userId != null && UUID_PATTERN.matcher(userId).matches();
    }

    /** Replaces any mapped audit fields with the actor for a newly created record. */
    static void stampCreate(Map<String, Object> recordData, String userId) {
        dropMappedAuditFields(recordData);
        if (!isStampable(userId)) {
            return;
        }
        recordData.put("createdBy", userId);
        recordData.put("updatedBy", userId);
    }

    /** Replaces any mapped audit fields with the actor as the updater for a record update. */
    static void stampUpdate(Map<String, Object> updateData, String userId) {
        dropMappedAuditFields(updateData);
        if (!isStampable(userId)) {
            return;
        }
        updateData.put("updatedBy", userId);
    }

    private static void dropMappedAuditFields(Map<String, Object> data) {
        data.remove("createdBy");
        data.remove("updatedBy");
    }
}
