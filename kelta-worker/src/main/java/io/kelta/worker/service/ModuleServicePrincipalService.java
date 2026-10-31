package io.kelta.worker.service;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.router.SystemCollectionCache;
import io.kelta.worker.listener.TenantProvisioningHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creates and deactivates the <b>service principal</b> of an external module install
 * ({@code specs/module-platform/9-external-modules.md} §3.3): the identity a module uses to call
 * the public API, so Cerbos object/field permissions, RLS and {@code created_by} audit apply to it
 * exactly as to a person.
 *
 * <p>A principal is three rows, written in one transaction under the tenant:
 * <ul>
 *   <li>a {@code platform_user} with {@code user_type = 'SERVICE'}, an email under the reserved
 *       {@code .invalid} TLD and no credential — it never signs in, never receives mail, is left
 *       out of user lists and seat counts;</li>
 *   <li>a profile generated from the manifest's {@code permissions} ({@link Permissions}) plus full
 *       object access on the collections the module provisions, written to
 *       {@code profile_object_permission}, {@code profile_field_permission} and
 *       {@code profile_system_permission} and compiled by {@link CerbosPolicyGenerator} like any
 *       other profile. {@code API_ACCESS} is always granted: the gateway requires it on every API
 *       call. It is a system profile, so the admin UI shows it read-only;</li>
 *   <li>a {@code connected_app} with {@code grant_types = ["client_credentials"]} whose
 *       {@code run_as_user_id} is that user. kelta-auth issues its tokens with the user's identity
 *       and profile. The plaintext client secret is kept in {@code client_secret_enc}
 *       ({@link EncryptionService}), JDBC-only like {@code run_as_user_id}, because the worker must
 *       request tokens with it ({@link #clientCredentials}).</li>
 * </ul>
 *
 * <p>These writes bypass the generic CRUD path, so after commit the service requests a Cerbos sync
 * (which broadcasts {@code kelta.cerbos.policies.changed.<tenantId>}) and publishes
 * {@code kelta.record.changed} for the profile and connected-app collections, so every pod evicts
 * its cached responses. The user write publishes nothing: a record event on {@code users} would
 * start tenant flows and webhooks written for people.
 *
 * <p>Called by the external module install ({@code ExternalModuleService}): {@link #create} on
 * install, {@link #updatePermissions} on upgrade, {@link #deactivate} on uninstall.
 */
@Service
public class ModuleServicePrincipalService {

    private static final Logger log = LoggerFactory.getLogger(ModuleServicePrincipalService.class);

    static final String SERVICE_USER_TYPE = "SERVICE";
    /** Reserved by RFC 2606: never resolves, and the email transport refuses it. */
    static final String SERVICE_EMAIL_DOMAIN = "service.kelta.invalid";
    static final String MANDATORY_PERMISSION = "API_ACCESS";
    static final Set<String> FIELD_VISIBILITIES = Set.of("VISIBLE", "READ_ONLY", "HIDDEN", "MASKED");
    static final String CLIENT_ID_PREFIX = "kmod_";

    /** The manifest's module id rule (§3.1). */
    private static final Pattern MODULE_KEY = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");

    private static final String SELECT_VISIBLE_COLLECTION_ID = """
            SELECT id FROM collection
            WHERE name = ? AND active = true AND (tenant_id = ? OR system_collection = true)
            ORDER BY system_collection ASC LIMIT 1
            """;
    private static final String SELECT_OWN_COLLECTION_ID = """
            SELECT id FROM collection
            WHERE name = ? AND active = true AND tenant_id = ? AND system_collection = false
            """;
    private static final String SELECT_FIELD_ID =
            "SELECT id FROM field WHERE collection_id = ? AND name = ? AND active = true";
    private static final String INSERT_USER = """
            INSERT INTO platform_user (id, tenant_id, email, first_name, status, profile_id, user_type,
                                       created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, 'SERVICE', ?, NOW(), NOW())
            """;
    private static final String INSERT_PROFILE = """
            INSERT INTO profile (id, tenant_id, name, description, is_system, created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, TRUE, ?, NOW(), NOW())
            """;
    private static final String INSERT_SYSTEM_PERMISSION = """
            INSERT INTO profile_system_permission (id, tenant_id, profile_id, permission_name, granted)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String INSERT_OBJECT_PERMISSION = """
            INSERT INTO profile_object_permission (id, tenant_id, profile_id, collection_id,
                                                   can_create, can_read, can_edit, can_delete)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_FIELD_PERMISSION = """
            INSERT INTO profile_field_permission (id, tenant_id, profile_id, collection_id, field_id, visibility)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_CONNECTED_APP = """
            INSERT INTO connected_app (id, tenant_id, name, description, client_id, client_secret_hash,
                                       client_secret_enc, redirect_uris, scopes, grant_types, require_pkce,
                                       consent_required, active, run_as_user_id, created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, '[]'::jsonb, '["api"]'::jsonb, '["client_credentials"]'::jsonb,
                    false, false, true, ?, ?, NOW(), NOW())
            """;
    private static final String SELECT_APP_IDS =
            "SELECT id FROM connected_app WHERE tenant_id = ? AND run_as_user_id = ?";
    private static final String DEACTIVATE_APPS = """
            UPDATE connected_app SET active = false, client_secret_enc = NULL, updated_at = NOW()
            WHERE tenant_id = ? AND run_as_user_id = ?
            """;
    private static final String DEACTIVATE_USER = """
            UPDATE platform_user SET status = 'INACTIVE', updated_at = NOW()
            WHERE id = ? AND tenant_id = ? AND user_type = 'SERVICE'
            """;
    private static final String SELECT_SERVICE_PROFILE_ID = """
            SELECT profile_id FROM platform_user
            WHERE id = ? AND tenant_id = ? AND user_type = 'SERVICE' AND status = 'ACTIVE'
            """;
    private static final String DELETE_OBJECT_PERMISSIONS =
            "DELETE FROM profile_object_permission WHERE tenant_id = ? AND profile_id = ?";
    private static final String DELETE_FIELD_PERMISSIONS =
            "DELETE FROM profile_field_permission WHERE tenant_id = ? AND profile_id = ?";
    private static final String UPDATE_SYSTEM_PERMISSION = """
            UPDATE profile_system_permission SET granted = ?
            WHERE tenant_id = ? AND profile_id = ? AND permission_name = ?
            """;
    private static final String SELECT_CLIENT_CREDENTIALS = """
            SELECT client_id, client_secret_enc FROM connected_app
            WHERE id = ? AND tenant_id = ? AND active = true AND run_as_user_id IS NOT NULL
            """;
    private static final String COUNT_ACTIVE_PRINCIPAL = """
            SELECT count(*) FROM connected_app a JOIN platform_user u ON u.id = a.run_as_user_id
            WHERE a.id = ? AND a.tenant_id = ? AND a.active = true AND a.run_as_user_id = ?
              AND u.tenant_id = ? AND u.user_type = 'SERVICE' AND u.status = 'ACTIVE'
            """;

    /** The {@code permissions} block of an external module manifest, minus {@code credentials}. */
    public record Permissions(List<ObjectGrant> objects, List<FieldGrant> fields, List<String> system) {
        public Permissions {
            objects = objects == null ? List.of() : List.copyOf(objects);
            fields = fields == null ? List.of() : List.copyOf(fields);
            system = system == null ? List.of() : List.copyOf(system);
        }
    }

    /** {@code permissions.objects[]}: CRUD on one collection, by name. */
    public record ObjectGrant(String collection, boolean create, boolean read, boolean edit, boolean delete) {}

    /** {@code permissions.fields[]}: one field's visibility ({@link #FIELD_VISIBILITIES}). */
    public record FieldGrant(String collection, String field, String visibility) {}

    /**
     * @param moduleKey          the manifest {@code id}
     * @param displayName        the manifest {@code name}, shown on the user, profile and app
     * @param permissions        what the admin approved
     * @param ownedCollections   collections the module provisions; granted full object access
     * @param createdBy          the installing user's id, or {@code null}
     */
    public record CreateRequest(String moduleKey, String displayName, Permissions permissions,
                                List<String> ownedCollections, String createdBy) {
        public CreateRequest {
            permissions = permissions == null ? new Permissions(null, null, null) : permissions;
            ownedCollections = ownedCollections == null ? List.of() : List.copyOf(ownedCollections);
        }
    }

    public record ServicePrincipal(String userId, String profileId, String connectedAppId, String clientId) {}

    /** What the worker presents to kelta-auth's token endpoint for a principal. */
    public record ClientCredentials(String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "ClientCredentials[clientId=" + clientId + ", clientSecret=***]";
        }
    }

    private record ObjectAccess(boolean create, boolean read, boolean edit, boolean delete) {
        ObjectAccess or(ObjectAccess other) {
            return new ObjectAccess(create || other.create, read || other.read,
                    edit || other.edit, delete || other.delete);
        }
    }

    private record ResolvedField(String collectionId, String fieldId, String visibility) {}

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<EncryptionService> encryptionService;
    private final CerbosPolicySyncCoalescer cerbosSync;
    private final UserAccessRevocationMarker revocationMarker;
    private final PlatformEventPublisher eventPublisher;
    private final ObjectProvider<SystemCollectionCache> systemCollectionCache;
    private final BCryptPasswordEncoder secretHasher = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public ModuleServicePrincipalService(JdbcTemplate jdbcTemplate,
                                         PlatformTransactionManager transactionManager,
                                         ObjectProvider<EncryptionService> encryptionService,
                                         CerbosPolicySyncCoalescer cerbosSync,
                                         UserAccessRevocationMarker revocationMarker,
                                         PlatformEventPublisher eventPublisher,
                                         ObjectProvider<SystemCollectionCache> systemCollectionCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.encryptionService = encryptionService;
        this.cerbosSync = cerbosSync;
        this.revocationMarker = revocationMarker;
        this.eventPublisher = eventPublisher;
        this.systemCollectionCache = systemCollectionCache;
    }

    /**
     * Creates tenant {@code tenantId}'s service principal for a module install.
     *
     * @throws IllegalArgumentException when the request names an unknown collection, field or
     *                                  system permission, an invalid visibility or module key
     * @throws IllegalStateException    when no encryption key is configured (the secret could not
     *                                  be kept); nothing is written
     */
    public ServicePrincipal create(String tenantId, CreateRequest request) {
        requireText(tenantId, "tenantId");
        if (request.moduleKey() == null || !MODULE_KEY.matcher(request.moduleKey()).matches()) {
            throw new IllegalArgumentException("Invalid module key: " + request.moduleKey());
        }
        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? request.moduleKey() : request.displayName().strip();
        Set<String> systemGrants = systemGrants(request.permissions().system());
        EncryptionService encryption = encryptionService.getIfAvailable();
        if (encryption == null) {
            throw new IllegalStateException(
                    "Cannot create a module service principal: no encryption key is configured");
        }

        String suffix = HexFormat.of().formatHex(randomBytes(4));
        String userId = UUID.randomUUID().toString();
        String profileId = UUID.randomUUID().toString();
        String appId = UUID.randomUUID().toString();
        String clientId = CLIENT_ID_PREFIX + HexFormat.of().formatHex(randomBytes(16));
        String clientSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        String label = "Module: " + displayName + " (" + suffix + ")";

        TenantContext.callWithTenant(tenantId, () -> transactionTemplate.execute(status -> {
            Map<String, ObjectAccess> objectGrants = objectGrants(tenantId, request);
            List<ResolvedField> fieldGrants = fieldGrants(tenantId, request.permissions().fields());

            jdbcTemplate.update(INSERT_PROFILE, profileId, tenantId, truncate(label, 255),
                    "Generated for module " + request.moduleKey() + "; managed by the module install.",
                    request.createdBy());
            for (String permission : TenantProvisioningHook.ALL_PERMISSIONS) {
                jdbcTemplate.update(INSERT_SYSTEM_PERMISSION, UUID.randomUUID().toString(), tenantId,
                        profileId, permission, systemGrants.contains(permission));
            }
            objectGrants.forEach((collectionId, access) -> jdbcTemplate.update(INSERT_OBJECT_PERMISSION,
                    UUID.randomUUID().toString(), tenantId, profileId, collectionId,
                    access.create(), access.read(), access.edit(), access.delete()));
            for (ResolvedField field : fieldGrants) {
                jdbcTemplate.update(INSERT_FIELD_PERMISSION, UUID.randomUUID().toString(), tenantId,
                        profileId, field.collectionId(), field.fieldId(), field.visibility());
            }

            jdbcTemplate.update(INSERT_USER, userId, tenantId, serviceEmail(request.moduleKey(), suffix),
                    truncate(displayName, 100), profileId, request.createdBy());

            jdbcTemplate.update(INSERT_CONNECTED_APP, appId, tenantId, truncate(label, 200),
                    truncate("Service principal of module " + request.moduleKey(), 500), clientId,
                    secretHasher.encode(clientSecret), encryption.encrypt(clientSecret), userId,
                    request.createdBy() != null ? request.createdBy() : userId);
            return null;
        }));

        cerbosSync.requestSync(tenantId);
        for (String collection : List.of("profiles", "profile-system-permissions",
                "profile-object-permissions", "profile-field-permissions")) {
            broadcast(tenantId, ChangeType.CREATED, collection, profileId);
        }
        broadcast(tenantId, ChangeType.CREATED, "connected-apps", appId);
        log.info("Created service principal for module {} in tenant {}: user {}, app {}",
                request.moduleKey(), tenantId, userId, appId);
        return new ServicePrincipal(userId, profileId, appId, clientId);
    }

    /**
     * Regenerates an existing principal's profile from a new manifest's permissions (module
     * upgrade): object and field grants are replaced, system grants rewritten, in one transaction.
     * The user, the connected app and its secret are untouched, so tokens keep working and pick up
     * the new profile on Cerbos's next sync.
     *
     * @param request {@code moduleKey}/{@code displayName}/{@code createdBy} are ignored
     * @throws IllegalArgumentException as {@link #create}, or when {@code userId} is not an active
     *                                  SERVICE user of the tenant; nothing is changed
     */
    public void updatePermissions(String tenantId, String userId, CreateRequest request) {
        requireText(tenantId, "tenantId");
        requireText(userId, "userId");
        Set<String> systemGrants = systemGrants(request.permissions().system());
        String profileId = TenantContext.callWithTenant(tenantId, () -> transactionTemplate.execute(status -> {
            List<String> profiles = jdbcTemplate.queryForList(SELECT_SERVICE_PROFILE_ID, String.class,
                    userId, tenantId);
            if (profiles.isEmpty() || profiles.get(0) == null) {
                throw new IllegalArgumentException("Not an active service principal of this tenant: " + userId);
            }
            String id = profiles.get(0);
            Map<String, ObjectAccess> objectGrants = objectGrants(tenantId, request);
            List<ResolvedField> fieldGrants = fieldGrants(tenantId, request.permissions().fields());

            jdbcTemplate.update(DELETE_OBJECT_PERMISSIONS, tenantId, id);
            jdbcTemplate.update(DELETE_FIELD_PERMISSIONS, tenantId, id);
            for (String permission : TenantProvisioningHook.ALL_PERMISSIONS) {
                if (jdbcTemplate.update(UPDATE_SYSTEM_PERMISSION, systemGrants.contains(permission),
                        tenantId, id, permission) == 0) {
                    jdbcTemplate.update(INSERT_SYSTEM_PERMISSION, UUID.randomUUID().toString(), tenantId,
                            id, permission, systemGrants.contains(permission));
                }
            }
            objectGrants.forEach((collectionId, access) -> jdbcTemplate.update(INSERT_OBJECT_PERMISSION,
                    UUID.randomUUID().toString(), tenantId, id, collectionId,
                    access.create(), access.read(), access.edit(), access.delete()));
            for (ResolvedField field : fieldGrants) {
                jdbcTemplate.update(INSERT_FIELD_PERMISSION, UUID.randomUUID().toString(), tenantId,
                        id, field.collectionId(), field.fieldId(), field.visibility());
            }
            return id;
        }));

        cerbosSync.requestSync(tenantId);
        for (String collection : List.of("profile-system-permissions",
                "profile-object-permissions", "profile-field-permissions")) {
            broadcast(tenantId, ChangeType.UPDATED, collection, profileId);
        }
        log.info("Regenerated the profile of service principal {} in tenant {}", userId, tenantId);
    }

    /**
     * Deactivates a service principal: its connected apps stop issuing tokens (and lose the kept
     * secret), the user becomes {@code INACTIVE}, and the gateway refuses the tokens already issued.
     * The profile is left in place.
     *
     * @throws IllegalArgumentException when {@code userId} is not a SERVICE user of the tenant
     */
    public void deactivate(String tenantId, String userId) {
        requireText(tenantId, "tenantId");
        requireText(userId, "userId");
        List<String> appIds = TenantContext.callWithTenant(tenantId, () -> transactionTemplate.execute(status -> {
            if (jdbcTemplate.update(DEACTIVATE_USER, userId, tenantId) == 0) {
                throw new IllegalArgumentException("Not a service principal of this tenant: " + userId);
            }
            List<String> ids = jdbcTemplate.queryForList(SELECT_APP_IDS, String.class, tenantId, userId);
            jdbcTemplate.update(DEACTIVATE_APPS, tenantId, userId);
            // Fires once the transaction commits.
            revocationMarker.markAfterCommit(tenantId, userId);
            return ids;
        }));
        for (String appId : appIds) {
            broadcast(tenantId, ChangeType.UPDATED, "connected-apps", appId);
        }
        log.info("Deactivated service principal {} in tenant {}", userId, tenantId);
    }

    /**
     * The client id and decrypted secret of an active service-principal app, for the worker's
     * token request; empty when there is no such app in the tenant.
     */
    public Optional<ClientCredentials> clientCredentials(String tenantId, String connectedAppId) {
        requireText(tenantId, "tenantId");
        List<Map<String, Object>> rows = TenantContext.callWithTenant(tenantId, () ->
                jdbcTemplate.queryForList(SELECT_CLIENT_CREDENTIALS, connectedAppId, tenantId));
        if (rows.isEmpty() || rows.get(0).get("client_secret_enc") == null) {
            return Optional.empty();
        }
        EncryptionService encryption = encryptionService.getIfAvailable();
        if (encryption == null) {
            throw new IllegalStateException(
                    "Cannot read a module client secret: no encryption key is configured");
        }
        return Optional.of(new ClientCredentials((String) rows.get(0).get("client_id"),
                encryption.decrypt((String) rows.get(0).get("client_secret_enc"))));
    }

    /**
     * Whether {@code connectedAppId} is an active service-principal app of {@code tenantId} that
     * runs as {@code userId}, an {@code ACTIVE} {@code SERVICE} user of the same tenant — checked
     * when a module calls back with its token, since a token outlives a deactivation by up to its
     * 15-minute lifetime.
     */
    public boolean isActivePrincipal(String tenantId, String connectedAppId, String userId) {
        if (tenantId == null || connectedAppId == null || userId == null) {
            return false;
        }
        Long count = TenantContext.callWithTenant(tenantId, () -> jdbcTemplate.queryForObject(
                COUNT_ACTIVE_PRINCIPAL, Long.class, connectedAppId, tenantId, userId, tenantId));
        return count != null && count == 1;
    }

    private static Set<String> systemGrants(List<String> requested) {
        Set<String> grants = new LinkedHashSet<>();
        grants.add(MANDATORY_PERMISSION);
        for (String permission : requested) {
            if (!TenantProvisioningHook.ALL_PERMISSIONS.contains(permission)) {
                throw new IllegalArgumentException("Unknown system permission: " + permission);
            }
            grants.add(permission);
        }
        return grants;
    }

    private Map<String, ObjectAccess> objectGrants(String tenantId, CreateRequest request) {
        Map<String, ObjectAccess> grants = new LinkedHashMap<>();
        for (ObjectGrant grant : request.permissions().objects()) {
            String collectionId = visibleCollectionId(tenantId, grant.collection());
            grants.merge(collectionId,
                    new ObjectAccess(grant.create(), grant.read(), grant.edit(), grant.delete()),
                    ObjectAccess::or);
        }
        ObjectAccess full = new ObjectAccess(true, true, true, true);
        for (String collection : request.ownedCollections()) {
            List<String> ids = jdbcTemplate.queryForList(SELECT_OWN_COLLECTION_ID, String.class,
                    collection, tenantId);
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("Unknown module-owned collection: " + collection);
            }
            grants.put(ids.get(0), full);
        }
        return grants;
    }

    private List<ResolvedField> fieldGrants(String tenantId, List<FieldGrant> requested) {
        return requested.stream().map(grant -> {
            if (grant.visibility() == null || !FIELD_VISIBILITIES.contains(grant.visibility())) {
                throw new IllegalArgumentException("Invalid visibility for " + grant.collection() + "."
                        + grant.field() + ": " + grant.visibility());
            }
            String collectionId = visibleCollectionId(tenantId, grant.collection());
            List<String> fieldIds = jdbcTemplate.queryForList(SELECT_FIELD_ID, String.class,
                    collectionId, grant.field());
            if (fieldIds.isEmpty()) {
                throw new IllegalArgumentException("Unknown field: " + grant.collection() + "." + grant.field());
            }
            return new ResolvedField(collectionId, fieldIds.get(0), grant.visibility());
        }).toList();
    }

    private String visibleCollectionId(String tenantId, String collection) {
        List<String> ids = jdbcTemplate.queryForList(SELECT_VISIBLE_COLLECTION_ID, String.class,
                collection, tenantId);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Unknown collection: " + collection);
        }
        return ids.get(0);
    }

    /**
     * Evicts this pod's cached responses for a collection the raw writes touched and publishes
     * {@code kelta.record.changed} for it, which every pod's cache listener (and the gateway's
     * response cache) evicts on.
     */
    private void broadcast(String tenantId, ChangeType changeType, String collection, String recordId) {
        SystemCollectionCache cache = systemCollectionCache.getIfAvailable();
        if (cache != null) {
            cache.evict(tenantId, collection);
        }
        RecordChangedPayload payload = new RecordChangedPayload();
        payload.setCollectionName(collection);
        payload.setRecordId(recordId);
        payload.setChangeType(changeType);
        PlatformEvent<RecordChangedPayload> event =
                EventFactory.createEvent("kelta.record.changed", payload);
        event.setTenantId(tenantId);
        eventPublisher.publish("kelta.record.changed." + tenantId + "." + collection, event);
    }

    static String serviceEmail(String moduleKey, String suffix) {
        return moduleKey + "." + suffix + "@" + SERVICE_EMAIL_DOMAIN;
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
