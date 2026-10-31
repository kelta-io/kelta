package io.kelta.worker.service;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.PlatformEventPublisher;
import io.kelta.runtime.router.SystemCollectionCache;
import io.kelta.worker.listener.TenantProvisioningHook;
import io.kelta.worker.service.ModuleServicePrincipalService.ClientCredentials;
import io.kelta.worker.service.ModuleServicePrincipalService.CreateRequest;
import io.kelta.worker.service.ModuleServicePrincipalService.FieldGrant;
import io.kelta.worker.service.ModuleServicePrincipalService.ObjectGrant;
import io.kelta.worker.service.ModuleServicePrincipalService.Permissions;
import io.kelta.worker.service.ModuleServicePrincipalService.ServicePrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ModuleServicePrincipalService")
class ModuleServicePrincipalServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String INSTALLER = "installer-1";

    /** Records every write and answers the lookups from maps; asserts each runs under the tenant. */
    static final class RecordingJdbcTemplate extends JdbcTemplate {
        final List<Object[]> updates = new ArrayList<>();
        final Map<String, String> visibleCollections = new HashMap<>();
        final Map<String, String> ownCollections = new HashMap<>();
        final Map<String, String> fields = new HashMap<>();
        List<String> appIds = List.of();
        List<Map<String, Object>> credentialRows = List.of();
        final Map<String, String> serviceProfiles = new HashMap<>();
        int deactivatedUsers = 1;
        boolean systemPermissionRowsExist = true;

        @Override
        public int update(String sql, Object... args) {
            assertThat(TenantContext.get()).isEqualTo(TENANT);
            Object[] row = new Object[args.length + 1];
            row[0] = sql;
            System.arraycopy(args, 0, row, 1, args.length);
            updates.add(row);
            if (sql.contains("UPDATE profile_system_permission")) {
                return systemPermissionRowsExist ? 1 : 0;
            }
            return sql.contains("UPDATE platform_user") ? deactivatedUsers : 1;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            assertThat(TenantContext.get()).isEqualTo(TENANT);
            String value;
            if (sql.contains("FROM field")) {
                value = fields.get(args[0] + "." + args[1]);
            } else if (sql.contains("system_collection = false")) {
                value = ownCollections.get((String) args[0]);
            } else if (sql.contains("FROM collection")) {
                value = visibleCollections.get((String) args[0]);
            } else if (sql.contains("FROM connected_app")) {
                return (List<T>) appIds;
            } else if (sql.contains("FROM platform_user")) {
                value = serviceProfiles.get((String) args[0]);
            } else {
                throw new AssertionError("Unexpected query: " + sql);
            }
            return value == null ? List.of() : (List<T>) List.of(value);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            assertThat(TenantContext.get()).isEqualTo(TENANT);
            return credentialRows;
        }

        long activePrincipals = 1;
        final List<Object[]> principalChecks = new ArrayList<>();
        String principalSql;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            assertThat(TenantContext.get()).isEqualTo(TENANT);
            principalSql = sql;
            principalChecks.add(args);
            return (T) Long.valueOf(activePrincipals);
        }

        List<Object[]> updatesTo(String table) {
            return updates.stream().filter(u -> ((String) u[0]).contains(table)).toList();
        }
    }

    private RecordingJdbcTemplate jdbc;
    private EncryptionService encryption;
    private ObjectProvider<EncryptionService> encryptionProvider;
    private CerbosPolicySyncCoalescer cerbosSync;
    private UserAccessRevocationMarker revocationMarker;
    private PlatformEventPublisher eventPublisher;
    private SystemCollectionCache cache;
    private ModuleServicePrincipalService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = new RecordingJdbcTemplate();
        jdbc.visibleCollections.put("users", "col-users");
        jdbc.visibleCollections.put("bookings", "col-bookings");
        jdbc.ownCollections.put("billing_subscriptions", "col-subs");
        jdbc.visibleCollections.put("billing_subscriptions", "col-subs");
        jdbc.fields.put("col-users.email", "fld-email");

        encryption = new EncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        encryptionProvider = mock(ObjectProvider.class);
        when(encryptionProvider.getIfAvailable()).thenReturn(encryption);
        cerbosSync = mock(CerbosPolicySyncCoalescer.class);
        revocationMarker = mock(UserAccessRevocationMarker.class);
        eventPublisher = mock(PlatformEventPublisher.class);
        cache = mock(SystemCollectionCache.class);
        ObjectProvider<SystemCollectionCache> cacheProvider = mock(ObjectProvider.class);
        when(cacheProvider.getIfAvailable()).thenReturn(cache);

        service = new ModuleServicePrincipalService(jdbc, mock(PlatformTransactionManager.class),
                encryptionProvider, cerbosSync, revocationMarker, eventPublisher, cacheProvider);
    }

    private static CreateRequest request(Permissions permissions) {
        return new CreateRequest("acme-sync", "Acme Sync", permissions, List.of("billing_subscriptions"),
                INSTALLER);
    }

    private static Permissions readUsersWithHiddenEmail() {
        return new Permissions(
                List.of(new ObjectGrant("users", false, true, false, false),
                        new ObjectGrant("bookings", true, true, false, false),
                        new ObjectGrant("bookings", false, false, true, false)),
                List.of(new FieldGrant("users", "email", "HIDDEN")),
                List.of("VIEW_SETUP"));
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("writes a SERVICE user with a reserved-domain email, no credential, bound to the generated profile")
        void writesServiceUser() {
            ServicePrincipal principal = service.create(TENANT, request(readUsersWithHiddenEmail()));

            List<Object[]> users = jdbc.updatesTo("INSERT INTO platform_user");
            assertThat(users).hasSize(1);
            Object[] user = users.get(0);
            assertThat((String) user[0]).contains("'SERVICE'").contains("'ACTIVE'");
            assertThat(user[1]).isEqualTo(principal.userId());
            assertThat(user[2]).isEqualTo(TENANT);
            assertThat((String) user[3]).startsWith("acme-sync.").endsWith("@service.kelta.invalid");
            assertThat(user[4]).isEqualTo("Acme Sync");
            assertThat(user[5]).isEqualTo(principal.profileId());
            assertThat(jdbc.updatesTo("user_credential")).isEmpty();
        }

        @Test
        @DisplayName("generates a system profile: API_ACCESS plus the requested system permissions, a row for every permission")
        void generatesProfileSystemPermissions() {
            ServicePrincipal principal = service.create(TENANT, request(readUsersWithHiddenEmail()));

            Object[] profile = jdbc.updatesTo("INSERT INTO profile ").get(0);
            assertThat((String) profile[0]).contains("TRUE");
            assertThat(profile[1]).isEqualTo(principal.profileId());
            assertThat((String) profile[3]).startsWith("Module: Acme Sync (");

            List<Object[]> system = jdbc.updatesTo("profile_system_permission");
            assertThat(system).hasSize(TenantProvisioningHook.ALL_PERMISSIONS.size());
            Map<Object, Object> granted = new HashMap<>();
            system.forEach(row -> granted.put(row[4], row[5]));
            assertThat(granted).containsEntry("API_ACCESS", true).containsEntry("VIEW_SETUP", true)
                    .containsEntry("MANAGE_USERS", false).containsEntry("MODIFY_ALL_DATA", false);
        }

        @Test
        @DisplayName("object grants merge per collection; owned collections get full CRUD; field visibility is kept")
        void objectAndFieldGrants() {
            service.create(TENANT, request(readUsersWithHiddenEmail()));

            Map<Object, List<Object>> objects = new HashMap<>();
            jdbc.updatesTo("profile_object_permission").forEach(row ->
                    objects.put(row[4], List.of(row[5], row[6], row[7], row[8])));
            assertThat(objects).containsOnlyKeys("col-users", "col-bookings", "col-subs");
            assertThat(objects.get("col-users")).containsExactly(false, true, false, false);
            assertThat(objects.get("col-bookings")).containsExactly(true, true, true, false);
            assertThat(objects.get("col-subs")).containsExactly(true, true, true, true);

            List<Object[]> fields = jdbc.updatesTo("profile_field_permission");
            assertThat(fields).hasSize(1);
            assertThat(fields.get(0)).containsSubsequence("col-users", "fld-email", "HIDDEN");
        }

        @Test
        @DisplayName("adds a client_credentials connected app run as the user, its secret hashed and kept encrypted")
        void connectedApp() {
            ServicePrincipal principal = service.create(TENANT, request(readUsersWithHiddenEmail()));

            Object[] app = jdbc.updatesTo("INSERT INTO connected_app").get(0);
            assertThat((String) app[0]).contains("'[\"client_credentials\"]'::jsonb").contains("'[]'::jsonb");
            assertThat(app[1]).isEqualTo(principal.connectedAppId());
            assertThat(app[5]).isEqualTo(principal.clientId());
            assertThat(principal.clientId()).startsWith("kmod_").doesNotContain("@");
            assertThat(app[8]).isEqualTo(principal.userId());
            assertThat(app[9]).isEqualTo(INSTALLER);

            String hash = (String) app[6];
            String ciphertext = (String) app[7];
            assertThat(hash).doesNotStartWith("{bcrypt}");
            String secret = encryption.decrypt(ciphertext);
            assertThat(new BCryptPasswordEncoder().matches(secret, hash)).isTrue();
            assertThat(ciphertext).doesNotContain(secret);
        }

        @Test
        @DisplayName("an install with no installing user records the service user as the app's creator")
        void noInstaller() {
            ServicePrincipal principal = service.create(TENANT,
                    new CreateRequest("acme-sync", null, null, null, null));

            Object[] app = jdbc.updatesTo("INSERT INTO connected_app").get(0);
            assertThat(app[9]).isEqualTo(principal.userId());
            assertThat(jdbc.updatesTo("profile_object_permission")).isEmpty();
        }

        @Test
        @DisplayName("requests a Cerbos sync and broadcasts the profile and connected-app changes after the writes")
        void syncsAndBroadcasts() {
            ServicePrincipal principal = service.create(TENANT, request(readUsersWithHiddenEmail()));

            verify(cerbosSync).requestSync(TENANT);
            for (String collection : List.of("profiles", "profile-system-permissions",
                    "profile-object-permissions", "profile-field-permissions", "connected-apps")) {
                verify(eventPublisher).publish(eq("kelta.record.changed." + TENANT + "." + collection), any());
                verify(cache).evict(TENANT, collection);
            }
            verify(eventPublisher, never()).publish(eq("kelta.record.changed." + TENANT + ".users"), any());
            assertThat(principal.userId()).isNotBlank();
        }

        @Test
        @DisplayName("refuses without an encryption key and writes nothing")
        void refusesWithoutEncryption() {
            when(encryptionProvider.getIfAvailable()).thenReturn(null);

            assertThatThrownBy(() -> service.create(TENANT, request(readUsersWithHiddenEmail())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.updates).isEmpty();
            verifyNoInteractions(cerbosSync, eventPublisher);
        }

        @Test
        @DisplayName("refuses unknown system permissions, collections, fields, visibilities and bad module keys")
        void refusesInvalidRequests() {
            List<CreateRequest> invalid = List.of(
                    request(new Permissions(null, null, List.of("ROOT"))),
                    request(new Permissions(List.of(new ObjectGrant("ghost", false, true, false, false)), null, null)),
                    request(new Permissions(null, List.of(new FieldGrant("users", "ghost", "HIDDEN")), null)),
                    request(new Permissions(null, List.of(new FieldGrant("users", "email", "SECRET")), null)),
                    new CreateRequest("acme-sync", "Acme", null, List.of("users"), INSTALLER),
                    new CreateRequest("Acme Sync", "Acme", null, null, INSTALLER));

            for (CreateRequest request : invalid) {
                assertThatThrownBy(() -> service.create(TENANT, request))
                        .as(request.toString()).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(jdbc.updates).isEmpty();
            verifyNoInteractions(cerbosSync, eventPublisher);
        }
    }

    @Nested
    @DisplayName("updatePermissions")
    class UpdatePermissions {

        @Test
        @DisplayName("replaces object and field grants and rewrites every system grant of the existing profile")
        void replacesGrants() {
            jdbc.serviceProfiles.put("svc-1", "profile-1");

            service.updatePermissions(TENANT, "svc-1", request(new Permissions(
                    List.of(new ObjectGrant("bookings", false, true, false, false)), List.of(),
                    List.of("MANAGE_USERS"))));

            assertThat(jdbc.updatesTo("DELETE FROM profile_object_permission")).hasSize(1)
                    .first().satisfies(row -> assertThat(row).containsSubsequence(TENANT, "profile-1"));
            assertThat(jdbc.updatesTo("DELETE FROM profile_field_permission")).hasSize(1);
            Map<Object, Object> granted = new HashMap<>();
            jdbc.updatesTo("UPDATE profile_system_permission").forEach(row -> granted.put(row[4], row[1]));
            assertThat(granted).hasSize(TenantProvisioningHook.ALL_PERMISSIONS.size())
                    .containsEntry("API_ACCESS", true).containsEntry("MANAGE_USERS", true)
                    .containsEntry("VIEW_SETUP", false);
            assertThat(jdbc.updatesTo("INSERT INTO profile_system_permission")).isEmpty();

            Map<Object, List<Object>> objects = new HashMap<>();
            jdbc.updatesTo("INSERT INTO profile_object_permission").forEach(row ->
                    objects.put(row[4], List.of(row[5], row[6], row[7], row[8])));
            assertThat(objects).containsOnlyKeys("col-bookings", "col-subs");
            assertThat(objects.get("col-subs")).containsExactly(true, true, true, true);
            assertThat(jdbc.updatesTo("INSERT INTO profile_field_permission")).isEmpty();
            // The user, its app and its secret are untouched.
            assertThat(jdbc.updatesTo("platform_user")).isEmpty();
            assertThat(jdbc.updatesTo("connected_app")).isEmpty();

            verify(cerbosSync).requestSync(TENANT);
            verify(eventPublisher).publish(eq("kelta.record.changed." + TENANT + ".profile-object-permissions"),
                    any());
        }

        @Test
        @DisplayName("inserts a system grant row a permission added since the profile was generated is missing")
        void insertsMissingSystemRows() {
            jdbc.serviceProfiles.put("svc-1", "profile-1");
            jdbc.systemPermissionRowsExist = false;

            service.updatePermissions(TENANT, "svc-1", request(readUsersWithHiddenEmail()));

            assertThat(jdbc.updatesTo("INSERT INTO profile_system_permission"))
                    .hasSize(TenantProvisioningHook.ALL_PERMISSIONS.size());
        }

        @Test
        @DisplayName("refuses a user that is not an active SERVICE user of the tenant, changing nothing")
        void refusesUnknownPrincipal() {
            assertThatThrownBy(() -> service.updatePermissions(TENANT, "person-1",
                    request(readUsersWithHiddenEmail())))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(jdbc.updates).isEmpty();
            verify(cerbosSync, never()).requestSync(any());
        }
    }

    @Nested
    @DisplayName("deactivate")
    class Deactivate {

        @Test
        @DisplayName("deactivates the user and its apps, drops the kept secret, revokes issued tokens")
        void deactivates() {
            jdbc.appIds = List.of("app-1");

            service.deactivate(TENANT, "svc-1");

            Object[] user = jdbc.updatesTo("UPDATE platform_user").get(0);
            assertThat((String) user[0]).contains("'INACTIVE'").contains("user_type = 'SERVICE'");
            assertThat(user).containsSubsequence("svc-1", TENANT);
            Object[] apps = jdbc.updatesTo("UPDATE connected_app").get(0);
            assertThat((String) apps[0]).contains("active = false").contains("client_secret_enc = NULL");
            verify(revocationMarker).markAfterCommit(TENANT, "svc-1");
            verify(eventPublisher).publish(eq("kelta.record.changed." + TENANT + ".connected-apps"), any());
        }

        @Test
        @DisplayName("refuses a user that is not a SERVICE user of the tenant")
        void refusesNonServiceUser() {
            jdbc.deactivatedUsers = 0;

            assertThatThrownBy(() -> service.deactivate(TENANT, "person-1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.updatesTo("UPDATE connected_app")).isEmpty();
            verify(revocationMarker, never()).markAfterCommit(any(), any());
            verify(eventPublisher, times(0)).publish(any(), any());
        }
    }

    @Nested
    @DisplayName("clientCredentials")
    class Credentials {

        @Test
        @DisplayName("returns the client id and decrypted secret of an active run-as app")
        void decrypts() {
            jdbc.credentialRows = List.of(Map.of("client_id", "kmod_abc",
                    "client_secret_enc", encryption.encrypt("s3cret")));

            Optional<ClientCredentials> credentials = service.clientCredentials(TENANT, "app-1");

            assertThat(credentials).contains(new ClientCredentials("kmod_abc", "s3cret"));
            assertThat(credentials.get().toString()).doesNotContain("s3cret");
        }

        @Test
        @DisplayName("is empty when there is no such active app")
        void emptyWhenAbsent() {
            assertThat(service.clientCredentials(TENANT, "app-1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("isActivePrincipal")
    class ActivePrincipal {

        @Test
        @DisplayName("is true only for an active app running as an ACTIVE SERVICE user of the same tenant")
        void checksAppUserAndTenant() {
            assertThat(service.isActivePrincipal(TENANT, "app-1", "user-1")).isTrue();

            assertThat(jdbc.principalChecks.getFirst()).containsExactly("app-1", TENANT, "user-1", TENANT);
            assertThat(jdbc.principalSql).contains("a.active = true", "a.run_as_user_id = ?",
                    "u.user_type = 'SERVICE'", "u.status = 'ACTIVE'", "u.tenant_id = ?");
        }

        @Test
        @DisplayName("is false when no such principal is active, and never queries without all three ids")
        void falseOtherwise() {
            jdbc.activePrincipals = 0;
            assertThat(service.isActivePrincipal(TENANT, "app-1", "user-1")).isFalse();

            assertThat(service.isActivePrincipal(TENANT, null, "user-1")).isFalse();
            assertThat(service.isActivePrincipal(TENANT, "app-1", null)).isFalse();
            assertThat(jdbc.principalChecks).hasSize(1);
        }
    }
}
