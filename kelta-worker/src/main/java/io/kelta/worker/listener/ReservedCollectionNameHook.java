package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.interceptor.GatewayStaticRoutes;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rejects a tenant collection whose name is a path segment the gateway already serves through a
 * {@code static-} route ({@code /api/<segment>/**}, see {@link GatewayStaticRoutes}).
 *
 * <p>The gateway matches those routes before collection routes, so a collection with such a name
 * would be reached through the static route and authorized by that route's rule rather than by
 * the collection's own permissions. System collections are declared in code and seeded directly,
 * so they never pass through this hook; a {@code systemCollection} row is left alone regardless.
 *
 * <p>Applies on create and on an update that changes the name.
 */
@Component
public class ReservedCollectionNameHook implements BeforeSaveHook {

    static final String RESERVED_COLLECTION_NAME = "RESERVED_COLLECTION_NAME";

    @Override
    public String getCollectionName() {
        return "collections";
    }

    /**
     * After the schema module's {@code CollectionLifecycleHook} (0), which validates the name's
     * format, and {@code CollectionOwnershipValidationHook} (1). Hooks are Spring-discovered, so
     * no two hooks on one collection may share an order.
     */
    @Override
    public int getOrder() {
        return 2;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validate(record.get("name"), record.get("systemCollection"));
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        if (!record.containsKey("name")) {
            return BeforeSaveResult.ok();
        }
        Object previousName = previous != null ? previous.get("name") : null;
        if (previousName != null && Objects.equals(previousName.toString().trim(), trimmed(record.get("name")))) {
            return BeforeSaveResult.ok();
        }
        Object system = record.containsKey("systemCollection") ? record.get("systemCollection")
                : previous != null ? previous.get("systemCollection") : null;
        return validate(record.get("name"), system);
    }

    private static BeforeSaveResult validate(Object rawName, Object systemCollection) {
        if (Boolean.TRUE.equals(systemCollection)) {
            return BeforeSaveResult.ok();
        }
        String name = trimmed(rawName);
        if (!GatewayStaticRoutes.isStaticSegment(name)) {
            return BeforeSaveResult.ok();
        }
        return BeforeSaveResult.errors(List.of(new BeforeSaveResult.ValidationError("name",
                "Collection name '" + name + "' is reserved by the platform API (/api/" + name
                        + "); choose another name", RESERVED_COLLECTION_NAME)));
    }

    private static String trimmed(Object value) {
        return value == null ? null : value.toString().trim();
    }
}
