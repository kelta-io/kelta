package io.kelta.worker.module;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.worker.service.FieldMaskingService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an external module's service principal may see and write, and the filter every record the
 * platform sends a module goes through ({@code specs/module-platform/9-external-modules.md} §3.4).
 * It evaluates the principal's generated profile the way {@code CerbosPolicyGenerator} compiles
 * it, for the module instead of the caller:
 *
 * <ol>
 *   <li><b>Server-only fields are always removed</b> ({@link FieldDefinition#serverOnly()}).</li>
 *   <li>With no {@code read} on the collection (no object grant, not owned, no
 *       {@code VIEW_ALL_DATA}) the module sees no attributes at all — a {@code *} hook on such a
 *       collection still runs, with an empty record.</li>
 *   <li>{@code HIDDEN} fields are removed; {@code MASKED} ones are masked with the field's masking
 *       config ({@code FULL} when it has none).</li>
 *   <li>A hook's {@code fieldUpdates} may name only defined fields that are neither server-only
 *       nor system-maintained and that the profile can write ({@code VISIBLE}), on a collection it
 *       may create in (create) or edit (update). Anything else — including the audit fields
 *       ({@code id}, {@code createdBy}, …), which no collection declares — is refused and the hook
 *       result is a module error.</li>
 * </ol>
 *
 * <p>A snapshot: loaded when the endpoint registry refreshes the install (on its
 * {@code module.changed} event and every reconcile), so an upgrade that regenerates the profile
 * reaches it within one reconcile on every pod.
 *
 * @param objects    object grants by collection name
 * @param fields     field visibility by collection name, then field name; absent = VISIBLE
 * @param viewAll    the profile holds {@code VIEW_ALL_DATA}
 * @param modifyAll  the profile holds {@code MODIFY_ALL_DATA}
 */
record ModulePrincipalAccess(Map<String, ObjectAccess> objects, Map<String, Map<String, String>> fields,
                             boolean viewAll, boolean modifyAll) {

    /** Nothing at all: the install has no (active) principal. */
    static final ModulePrincipalAccess NONE = new ModulePrincipalAccess(Map.of(), Map.of(), false, false);

    record ObjectAccess(boolean create, boolean read, boolean edit, boolean delete) {}

    private static final FieldMaskingService.MaskingConfig DEFAULT_MASK =
            new FieldMaskingService.MaskingConfig(FieldMaskingService.MaskType.FULL, '*', null);

    /** Loads the access of the SERVICE user {@code serviceUserId}'s profile. */
    static ModulePrincipalAccess load(JdbcTemplate jdbc, String tenantId, String serviceUserId) {
        if (serviceUserId == null) {
            return NONE;
        }
        List<String> profileIds = jdbc.queryForList(
                "SELECT profile_id FROM platform_user WHERE id = ? AND tenant_id = ? AND user_type = 'SERVICE' "
                        + "AND status = 'ACTIVE'", String.class, serviceUserId, tenantId);
        if (profileIds.isEmpty() || profileIds.getFirst() == null) {
            return NONE;
        }
        String profileId = profileIds.getFirst();
        Map<String, ObjectAccess> objects = new HashMap<>();
        jdbc.query("SELECT c.name, p.can_create, p.can_read, p.can_edit, p.can_delete "
                        + "FROM profile_object_permission p JOIN collection c ON c.id = p.collection_id "
                        + "WHERE p.profile_id = ? AND p.tenant_id = ?",
                rs -> {
                    objects.put(rs.getString(1), new ObjectAccess(rs.getBoolean(2), rs.getBoolean(3),
                            rs.getBoolean(4), rs.getBoolean(5)));
                }, profileId, tenantId);
        Map<String, Map<String, String>> fields = new HashMap<>();
        jdbc.query("SELECT c.name, f.name, p.visibility FROM profile_field_permission p "
                        + "JOIN field f ON f.id = p.field_id JOIN collection c ON c.id = p.collection_id "
                        + "WHERE p.profile_id = ? AND p.tenant_id = ?",
                rs -> {
                    fields.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getString(3));
                }, profileId, tenantId);
        Set<String> system = Set.copyOf(jdbc.queryForList(
                "SELECT permission_name FROM profile_system_permission WHERE profile_id = ? AND granted = true",
                String.class, profileId));
        return new ModulePrincipalAccess(Map.copyOf(objects), Map.copyOf(fields),
                system.contains("VIEW_ALL_DATA"), system.contains("MODIFY_ALL_DATA"));
    }

    boolean canRead(String collection) {
        ObjectAccess access = objects.get(collection);
        return viewAll || modifyAll || (access != null && access.read());
    }

    private boolean canWrite(String collection, boolean create) {
        if (modifyAll) {
            return true;
        }
        ObjectAccess access = objects.get(collection);
        return access != null && (create ? access.create() : access.edit());
    }

    private String visibility(String collection, String field) {
        return fields.getOrDefault(collection, Map.of()).getOrDefault(field, "VISIBLE");
    }

    /**
     * The record as the module may see it: a filtered copy, never the original. {@code definition}
     * may be null (an unregistered collection), in which case only the visibility rules apply.
     */
    Map<String, Object> outbound(String collection, CollectionDefinition definition, Map<String, Object> record,
                                 FieldMaskingService masking) {
        if (record == null) {
            return null;
        }
        if (!canRead(collection)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String name = entry.getKey();
            FieldDefinition field = definition == null ? null : definition.getField(name);
            if (field != null && field.serverOnly()) {
                continue;
            }
            String visibility = visibility(collection, name);
            if ("HIDDEN".equals(visibility)) {
                continue;
            }
            if ("MASKED".equals(visibility) && entry.getValue() != null) {
                FieldMaskingService.MaskingConfig config = masking == null || field == null
                        ? DEFAULT_MASK : masking.configFor(field).orElse(DEFAULT_MASK);
                String value = String.valueOf(entry.getValue());
                out.put(name, masking == null ? "******" : masking.mask(value, config));
                continue;
            }
            out.put(name, entry.getValue());
        }
        return out;
    }

    /**
     * The names of {@code fields} the module may know about — an event's {@code changedFields}:
     * none without {@code read} on the collection, never a server-only or {@code HIDDEN} one
     * (naming a field it cannot see would leak that it changed).
     */
    List<String> visibleFields(String collection, CollectionDefinition definition, List<String> fields) {
        if (fields == null || !canRead(collection)) {
            return List.of();
        }
        List<String> visible = new ArrayList<>();
        for (String name : fields) {
            FieldDefinition field = definition == null ? null : definition.getField(name);
            if ((field == null || !field.serverOnly()) && !"HIDDEN".equals(visibility(collection, name))) {
                visible.add(name);
            }
        }
        return visible;
    }

    /** The fields of {@code updates} the module may not set, in their order; empty when all are allowed. */
    List<String> unwritable(String collection, CollectionDefinition definition, Map<String, Object> updates,
                            boolean create) {
        List<String> refused = new ArrayList<>();
        boolean objectAllowed = canWrite(collection, create);
        for (String name : updates.keySet()) {
            FieldDefinition field = definition == null ? null : definition.getField(name);
            if (!objectAllowed || field == null || field.serverOnly() || field.systemMaintained()
                    || !"VISIBLE".equals(visibility(collection, name))) {
                refused.add(name);
            }
        }
        return refused;
    }
}
