package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.repository.EnvironmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Provisions <b>tenant-backed sandboxes</b>: a sandbox is a real tenant linked
 * to its production (parent) tenant via {@code tenant.parent_tenant_id} (V157),
 * created through the standard tenant write path so the whole lifecycle hook
 * chain runs (schema creation, profile/OIDC/admin seeding, Cerbos sync, Svix,
 * Superset). Configuration is then cloned in by exporting a full metadata
 * package from the parent and importing it into the sandbox tenant.
 *
 * <p>Tenant-context discipline: cross-tenant hops are always explicit
 * {@link TenantContext#callWithTenant}/{@link TenantContext#runWithTenant} with
 * concrete tenant ids — the only shape that works. A sentinel-binding
 * {@code runAsPlatform} helper used to exist and matched no RLS policy (reads
 * returned zero rows silently); it was removed 2026-09-09.
 */
@Service
public class SandboxProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(SandboxProvisioningService.class);

    /** Mirrors the tenant.slug DB constraint (V8) and the gateway slug pattern. */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,61}[a-z0-9]$");
    private static final String SUBJECT_ENV_CHANGED = "kelta.config.environment.changed";
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 20;

    private final EnvironmentRepository environmentRepository;
    private final SandboxEnvironmentService environmentService;
    private final PackageService packageService;
    private final PackageImportService packageImportService;
    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final PlatformEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public SandboxProvisioningService(EnvironmentRepository environmentRepository,
                                      SandboxEnvironmentService environmentService,
                                      PackageService packageService,
                                      PackageImportService packageImportService,
                                      QueryEngine queryEngine,
                                      CollectionRegistry collectionRegistry,
                                      PlatformEventPublisher eventPublisher,
                                      ObjectMapper objectMapper) {
        this.environmentRepository = environmentRepository;
        this.environmentService = environmentService;
        this.packageService = packageService;
        this.packageImportService = packageImportService;
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a sandbox tenant for the given parent and starts the async clone.
     * The parent tenant id comes from the authenticated request context — never
     * from the request body (a caller must not be able to claim another tenant
     * as parent).
     *
     * @return the environment row plus one-time credentials for the seeded
     *         sandbox admin ({@code adminInitialPassword} is never persisted or
     *         retrievable again)
     */
    public Map<String, Object> createSandbox(String parentTenantId, String name, String description,
                                             String type, String createdBy) {
        Map<String, Object> parent = environmentRepository.getJdbcTemplate().queryForList(
                        "SELECT * FROM tenant WHERE id = ?", parentTenantId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Parent tenant not found"));

        if (parent.get("parent_tenant_id") != null) {
            throw new IllegalArgumentException("Sandboxes cannot be created from a sandbox tenant");
        }

        String parentSlug = (String) parent.get("slug");
        String normalized = normalizeName(name);
        String sandboxSlug = parentSlug + "--" + normalized;
        if (!SLUG_PATTERN.matcher(sandboxSlug).matches()) {
            throw new IllegalArgumentException(
                    "Sandbox slug '" + sandboxSlug + "' is invalid — the combined parent slug + '--' + name "
                            + "must be at most 63 characters of [a-z0-9-]. Use a shorter name (max "
                            + Math.max(0, 61 - parentSlug.length() - 2) + " characters for this tenant).");
        }
        Integer slugCount = environmentRepository.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE slug = ?", Integer.class, sandboxSlug);
        if (slugCount != null && slugCount > 0) {
            throw new IllegalArgumentException("A tenant with slug '" + sandboxSlug + "' already exists");
        }
        if (environmentRepository.existsByTenantAndName(parentTenantId, name)) {
            throw new IllegalArgumentException("Environment with name '" + name + "' already exists");
        }

        // Create the sandbox tenant through the standard tenants write path so
        // the full lifecycle hook chain fires. Runs under the caller's context —
        // the tenant table is platform-scoped (no RLS).
        CollectionDefinition tenantsDef = collectionRegistry.get("tenants");
        if (tenantsDef == null) {
            throw new IllegalStateException("Tenants system collection not initialized");
        }
        Map<String, Object> tenantData = new LinkedHashMap<>();
        tenantData.put("slug", sandboxSlug);
        tenantData.put("name", parent.get("name") + " (" + name + ")");
        tenantData.put("edition", parent.get("edition"));
        // JSONB columns come back from JDBC as PGobject — the QueryEngine's
        // JSON-field validation requires actual structures, not strings.
        tenantData.put("settings", jsonValue(parent.get("settings")));
        tenantData.put("limits", jsonValue(parent.get("limits")));
        // Sandboxes inherit the parent's network restrictions: the IP-allowlist
        // filter fails open on missing config, so a fresh tenant would otherwise
        // expose a clone of production config to any IP.
        tenantData.put("ipAllowlistEnabled", parent.get("ip_allowlist_enabled"));
        tenantData.put("ipAllowlistCidrs", jsonValue(parent.get("ip_allowlist_cidrs")));
        tenantData.put("parentTenantId", parentTenantId);
        tenantData.values().removeIf(Objects::isNull);

        Map<String, Object> createdTenant = queryEngine.create(tenantsDef, tenantData);
        String sandboxTenantId = String.valueOf(createdTenant.get("id"));

        // The provisioning hook seeds the admin with a well-known default
        // password hash — replace it with a random one-time secret.
        String initialPassword = randomPassword();
        hardenSandboxAdmin(sandboxTenantId, sandboxSlug, initialPassword);

        String prodEnvId = (String) environmentService
                .ensureProductionEnvironment(parentTenantId, createdBy).get("id");
        String envId = environmentRepository.createWithSandboxTenant(
                parentTenantId, name, description,
                "STAGING".equals(type) ? "STAGING" : "SANDBOX",
                prodEnvId, sandboxTenantId, createdBy);

        cloneIntoSandbox(parentTenantId, sandboxTenantId, envId);

        Map<String, Object> result = new LinkedHashMap<>(
                environmentRepository.findByIdAndTenant(envId, parentTenantId).orElseThrow());
        result.put("sandboxSlug", sandboxSlug);
        result.put("adminUsername", sandboxSlug + "-admin");
        result.put("adminEmail", sandboxSlug + "-admin@kelta.local");
        result.put("adminInitialPassword", initialPassword);
        publishEnvironmentEvent(parentTenantId, envId, "CREATED");
        return result;
    }

    /**
     * Async clone: export the parent's full metadata package under the parent's
     * tenant context, import it into the sandbox under the sandbox's context.
     * Every phase binds its concrete tenant explicitly — @Async carries no
     * ambient tenant scope.
     */
    @Async("applicationTaskExecutor")
    public void cloneIntoSandbox(String parentTenantId, String sandboxTenantId, String envId) {
        try {
            // Slug is required in the tenant context: user-collection physical
            // tables live in the tenant's schema (named by slug), so an import
            // hop bound to id-only would create the sandbox's tables in the
            // public schema. Resolve both slugs and bind them.
            String parentSlug = tenantSlug(parentTenantId);
            String sandboxSlug = tenantSlug(sandboxTenantId);

            Map<String, Object> pkg = TenantContext.callWithTenant(parentTenantId, parentSlug, () ->
                    packageService.exportPackage(parentTenantId,
                            packageService.exportAllOptions(parentTenantId, "sandbox-clone", "1.0.0")));

            var report = TenantContext.callWithTenant(sandboxTenantId, sandboxSlug, () ->
                    packageImportService.importPackage(sandboxTenantId, pkg,
                            new PackageImportService.ImportOptions(
                                    PackageImportService.ConflictMode.OVERWRITE, false, null, null, null)));

            boolean ok = report.failed() == 0;
            if (!ok) {
                // Surface the failed items so a stuck clone can be diagnosed
                // (env row has no error column — record onto config).
                String detail = report.items().stream()
                        .filter(it -> "FAILED".equals(it.action()))
                        .map(it -> it.type() + " " + it.naturalKey() + ": " + it.error())
                        .limit(20)
                        .reduce((a, b) -> a + " | " + b)
                        .orElse("unknown");
                log.error("Sandbox clone import errors for env {} (sandboxTenant={}): {}",
                        envId, sandboxTenantId, detail);
                recordCloneError(parentTenantId, envId, detail);
            }
            TenantContext.runWithTenant(parentTenantId, () ->
                    environmentRepository.updateStatus(envId, parentTenantId, ok ? "ACTIVE" : "FAILED"));
            log.info("Sandbox clone finished for env {} (sandboxTenant={}): created={}, updated={}, failed={}",
                    envId, sandboxTenantId, report.created(), report.updated(), report.failed());
            publishEnvironmentEvent(parentTenantId, envId, ok ? "CLONED" : "CLONE_FAILED");
        } catch (Exception e) {
            log.error("Sandbox clone failed for env {} (sandboxTenant={})", envId, sandboxTenantId, e);
            try {
                TenantContext.runWithTenant(parentTenantId, () ->
                        environmentRepository.updateStatus(envId, parentTenantId, "FAILED"));
            } catch (Exception inner) {
                log.error("Failed to mark environment {} FAILED", envId, inner);
            }
            publishEnvironmentEvent(parentTenantId, envId, "CLONE_FAILED");
        }
    }

    /**
     * Registers a remote environment: a promotion target on another cluster,
     * described by base URL + tenant slug + a vault credential reference. No
     * tenant is created — the remote installation owns its own tenants.
     */
    public Map<String, Object> createRemoteEnvironment(String tenantId, String name, String description,
                                                       String type, String remoteBaseUrl,
                                                       String remoteTenantSlug, String credentialRef,
                                                       String createdBy) {
        RemotePromotionClient.validateRemoteBaseUrl(remoteBaseUrl);
        if (remoteTenantSlug == null || remoteTenantSlug.isBlank()) {
            throw new IllegalArgumentException("remoteTenantSlug is required for a remote environment");
        }
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new IllegalArgumentException(
                    "credentialRef is required — the remote PAT must come from the credential vault");
        }
        if (environmentRepository.existsByTenantAndName(tenantId, name)) {
            throw new IllegalArgumentException("Environment with name '" + name + "' already exists");
        }

        String envType = type == null || type.isBlank() ? "PRODUCTION" : type;
        String envId = environmentRepository.createRemote(tenantId, name, description, envType,
                remoteBaseUrl, remoteTenantSlug, credentialRef, createdBy);
        publishEnvironmentEvent(tenantId, envId, "CREATED");
        return environmentRepository.findByIdAndTenant(envId, tenantId).orElseThrow();
    }

    /** Re-clones the parent's current config into the sandbox (OVERWRITE). */
    public Map<String, Object> refreshSandbox(String envId, String parentTenantId) {
        Map<String, Object> env = requireLocalSandbox(envId, parentTenantId);
        String sandboxTenantId = (String) env.get("sandbox_tenant_id");
        environmentRepository.updateStatus(envId, parentTenantId, "REFRESHING");
        cloneIntoSandbox(parentTenantId, sandboxTenantId, envId);
        publishEnvironmentEvent(parentTenantId, envId, "REFRESHING");
        return environmentRepository.findByIdAndTenant(envId, parentTenantId).orElseThrow();
    }

    /** Archives the env row and decommissions the backing sandbox tenant. */
    public void deleteSandbox(String envId, String parentTenantId) {
        Map<String, Object> env = requireLocalSandbox(envId, parentTenantId);
        String sandboxTenantId = (String) env.get("sandbox_tenant_id");
        environmentRepository.getJdbcTemplate().update(
                "UPDATE tenant SET status = 'DECOMMISSIONED', updated_at = NOW() WHERE id = ?",
                sandboxTenantId);
        environmentRepository.updateStatus(envId, parentTenantId, "ARCHIVED");
        publishEnvironmentEvent(parentTenantId, envId, "ARCHIVED");
        log.info("Archived sandbox env {} and decommissioned tenant {}", envId, sandboxTenantId);
    }

    /**
     * Real cross-tenant diff: exports both sides' full packages (each under its
     * own tenant context) and compares items by natural key.
     */
    public Map<String, Object> compareWithParent(String envId, String parentTenantId) {
        Map<String, Object> env = requireLocalSandbox(envId, parentTenantId);
        String sandboxTenantId = (String) env.get("sandbox_tenant_id");

        Map<String, Object> sandboxPkg = TenantContext.callWithTenant(sandboxTenantId, () ->
                packageService.exportPackage(sandboxTenantId,
                        packageService.exportAllOptions(sandboxTenantId, "diff-source", "0")));
        Map<String, Object> parentPkg = TenantContext.callWithTenant(parentTenantId, () ->
                packageService.exportPackage(parentTenantId,
                        packageService.exportAllOptions(parentTenantId, "diff-target", "0")));

        return diffPackages(env, sandboxPkg, parentPkg);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> diffPackages(Map<String, Object> env,
                                             Map<String, Object> sourcePkg,
                                             Map<String, Object> targetPkg) {
        Map<String, Map<String, Object>> source = indexByKey(
                (java.util.List<Map<String, Object>>) sourcePkg.getOrDefault("items", java.util.List.of()));
        Map<String, Map<String, Object>> target = indexByKey(
                (java.util.List<Map<String, Object>>) targetPkg.getOrDefault("items", java.util.List.of()));

        java.util.List<Map<String, Object>> changes = new java.util.ArrayList<>();
        for (var entry : source.entrySet()) {
            Map<String, Object> item = entry.getValue();
            if (!target.containsKey(entry.getKey())) {
                changes.add(changeEntry("ADD", item));
            } else if (!comparableData(item).equals(comparableData(target.get(entry.getKey())))) {
                changes.add(changeEntry("MODIFY", item));
            }
        }
        for (var entry : target.entrySet()) {
            if (!source.containsKey(entry.getKey())) {
                changes.add(changeEntry("REMOVE", entry.getValue()));
            }
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("status", "COMPARED");
        diff.put("environmentId", env.get("id"));
        diff.put("changes", changes);
        return diff;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> indexByKey(java.util.List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (var item : items) {
            String type = (String) item.get("type");
            Map<String, Object> data = (Map<String, Object>) item.get("data");
            index.put(type + ":" + PackageImportService.naturalKeyFor(type, data), item);
        }
        return index;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> comparableData(Map<String, Object> item) {
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) item.get("data"));
        // Ids and timestamps differ across tenants by construction
        data.remove("id");
        data.remove("created_at");
        data.remove("updated_at");
        data.keySet().removeIf(k -> k.endsWith("_id"));
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> changeEntry(String action, Map<String, Object> item) {
        String type = (String) item.get("type");
        Map<String, Object> data = (Map<String, Object>) item.get("data");
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("action", action);
        change.put("type", type);
        change.put("name", PackageImportService.naturalKeyFor(type, data));
        return change;
    }

    Map<String, Object> requireLocalSandbox(String envId, String parentTenantId) {
        Map<String, Object> env = environmentRepository.findByIdAndTenant(envId, parentTenantId)
                .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + envId));
        if (env.get("sandbox_tenant_id") == null) {
            throw new IllegalArgumentException(
                    "Environment is not a tenant-backed local sandbox: " + envId);
        }
        return env;
    }

    private void hardenSandboxAdmin(String sandboxTenantId, String sandboxSlug, String password) {
        String hash = passwordEncoder.encode(password);
        int updated = environmentRepository.getJdbcTemplate().update(
                "UPDATE user_credential SET password_hash = ? " +
                        "WHERE user_id = (SELECT id FROM platform_user WHERE tenant_id = ? AND username = ?)",
                hash, sandboxTenantId, sandboxSlug + "-admin");
        if (updated == 0) {
            log.warn("Could not harden sandbox admin credential for tenant {} — seeded user not found",
                    sandboxTenantId);
        }
    }

    private String randomPassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(secureRandom.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Sandbox name is required");
        }
        String normalized = name.toLowerCase()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Sandbox name must contain letters or digits");
        }
        return normalized;
    }

    /** Resolves a tenant's slug — needed to bind schema-per-tenant context. */
    String tenantSlug(String tenantId) {
        var rows = environmentRepository.getJdbcTemplate().queryForList(
                "SELECT slug FROM tenant WHERE id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Tenant not found: " + tenantId);
        }
        return (String) rows.get(0).get("slug");
    }

    /**
     * Parses a JDBC JSONB value (PGobject/String) back into a structure the
     * QueryEngine's JSON-field validation accepts. Null/empty → null.
     */
    private Object jsonValue(Object value) {
        if (value == null) {
            return null;
        }
        String json = value.toString();
        if (json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("Could not parse parent tenant JSONB value; omitting from sandbox: {}", e.getMessage());
            return null;
        }
    }

    private void recordCloneError(String parentTenantId, String envId, String detail) {
        try {
            String cfg = objectMapper.writeValueAsString(Map.of("cloneError", detail));
            TenantContext.runWithTenant(parentTenantId, () ->
                    environmentRepository.getJdbcTemplate().update(
                            "UPDATE environment SET config = ?::jsonb, updated_at = NOW() " +
                                    "WHERE id = ? AND tenant_id = ?",
                            cfg, envId, parentTenantId));
        } catch (Exception e) {
            log.warn("Could not record clone error for env {}: {}", envId, e.getMessage());
        }
    }

    private void publishEnvironmentEvent(String tenantId, String envId, String changeType) {
        try {
            Map<String, Object> payload = Map.of(
                    "environmentId", envId,
                    "changeType", changeType
            );
            PlatformEvent<Map<String, Object>> event = EventFactory.createEvent("environment.changed", payload);
            event.setTenantId(tenantId);
            eventPublisher.publish(SUBJECT_ENV_CHANGED + "." + tenantId + "." + envId, event);
        } catch (Exception e) {
            log.error("Failed to publish environment event for {} (tenant={})", envId, tenantId, e);
        }
    }
}
