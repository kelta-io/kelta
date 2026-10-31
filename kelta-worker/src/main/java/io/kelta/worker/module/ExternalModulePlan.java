package io.kelta.worker.module;

import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.TenantModuleData;

import java.util.List;
import java.util.Map;

/**
 * An external module install or upgrade plan ({@code specs/module-platform/9-external-modules.md}
 * §3.5): what applying the module's manifest in a tenant would grant and do. {@code attributes}
 * is the JSON:API {@code module-install-plans} body the admin consents to, {@code planHash}
 * included; the rest is what the apply needs and is never serialised.
 *
 * @param manifest     the parsed manifest, or {@code null} when it did not parse (the plan then
 *                     carries an {@code INVALID_MANIFEST} blocker)
 * @param manifestJson the manifest exactly as fetched
 * @param installed    the tenant's current row for the module id, or {@code null}
 */
public record ExternalModulePlan(Map<String, Object> attributes, ExternalModuleManifest manifest,
                                 String manifestJson, String baseUrl, TenantModuleData installed) {

    public static final String MODE_INSTALL = "INSTALL";
    public static final String MODE_UPGRADE = "UPGRADE";
    public static final String MODE_NO_OP = "NO_OP";
    public static final String MODE_BLOCKED = "BLOCKED";

    public String planHash() {
        return (String) attributes.get("planHash");
    }

    public String mode() {
        return (String) attributes.get("mode");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> blockers() {
        return (List<Map<String, Object>>) attributes.get("blockers");
    }

    /** {@code CREATE}/{@code KEEP} collections: the module owns them and gets full access to them. */
    @SuppressWarnings("unchecked")
    public List<String> ownedCollections() {
        return (List<String>) ((Map<String, Object>) attributes.get("grants")).get("ownedCollections");
    }

    /** Thrown when an apply's {@code planHash} no longer matches: {@code 409} with the fresh plan. */
    public static class PlanChangedException extends RuntimeException {
        private final transient Map<String, Object> plan;

        public PlanChangedException(Map<String, Object> plan) {
            super("The plan changed since it was reviewed; review the new plan and send its planHash");
            this.plan = plan;
        }

        public Map<String, Object> plan() {
            return plan;
        }
    }

    /** Thrown when a plan has blockers: {@code 422} with the plan, {@code errors[].code} per blocker. */
    public static class PlanBlockedException extends RuntimeException {
        private final transient Map<String, Object> plan;
        private final transient List<Map<String, Object>> blockers;

        public PlanBlockedException(Map<String, Object> plan, List<Map<String, Object>> blockers) {
            super("The plan has blockers: " + blockers.stream().map(b -> String.valueOf(b.get("code"))).toList());
            this.plan = plan;
            this.blockers = blockers;
        }

        public Map<String, Object> plan() {
            return plan;
        }

        public List<Map<String, Object>> blockers() {
            return blockers;
        }
    }
}
