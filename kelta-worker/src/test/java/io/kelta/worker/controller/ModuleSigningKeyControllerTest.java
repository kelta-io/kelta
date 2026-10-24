package io.kelta.worker.controller;

import io.kelta.runtime.module.ModuleSigningKey;
import io.kelta.runtime.module.ModuleSigningKeyStore;
import io.kelta.worker.interceptor.PermissionGatedControllers;
import io.kelta.worker.module.ModuleSignatureVerifier;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ModuleSigningKeyController")
class ModuleSigningKeyControllerTest {

    private static final String TENANT = "tenant-a";

    private FakeStore store;
    private BootstrapRepository bootstrapRepository;
    private HttpServletRequest request;
    private ModuleSigningKeyController controller;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        CerbosPermissionResolver permissionResolver = mock(CerbosPermissionResolver.class);
        bootstrapRepository = mock(BootstrapRepository.class);
        request = mock(HttpServletRequest.class);
        when(permissionResolver.getProfileId(request)).thenReturn("profile-1");
        grant(true);
        controller = new ModuleSigningKeyController(
                store, new ModuleSignatureVerifier("", "Ed25519"), permissionResolver, bootstrapRepository);
    }

    private void grant(boolean granted) {
        when(bootstrapRepository.findProfileSystemPermissions("profile-1")).thenReturn(List.of(
                Map.of("permission_name", "MANAGE_CREDENTIALS", "granted", granted)));
    }

    @Test
    @DisplayName("gates every write on MANAGE_CREDENTIALS, as the gateway does; the key list stays open")
    void everyHandlerIsPermissionGated() {
        grant(false);

        PermissionGatedControllers.assertEveryHandlerForbidden(controller, request, "listKeys");
        assertThat(store.keys).isEmpty();
    }

    private static String pem() throws Exception {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> data(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resource(Map<String, Object> body) {
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> resource) {
        return (Map<String, Object>) resource.get("attributes");
    }

    @Test
    @DisplayName("listed keys carry their id, so retire/activate/delete can address them")
    void listingCarriesId() throws Exception {
        // The bug this pins: keyToMap omitted "id", and JsonApiResponseBuilder.toResource() lifts
        // "id" out of the record map — so every listed key serialised as "id": null and the whole
        // rotation workflow was unreachable from the listing.
        controller.addKey(request, TENANT, "admin", Map.of("label", "2026-h2", "publicKeyPem", pem()));

        Map<String, Object> body = controller.listKeys(TENANT).getBody();
        assertThat(data(body)).hasSize(1);
        assertThat(data(body).get(0).get("id")).isNotNull();
        assertThat(data(body).get(0).get("id")).isEqualTo(store.keys.get(0).id());
    }

    @Test
    @DisplayName("id is not duplicated inside attributes — JSON:API forbids it there")
    void idIsNotInAttributes() throws Exception {
        controller.addKey(request, TENANT, "admin", Map.of("label", "2026-h2", "publicKeyPem", pem()));

        Map<String, Object> listed = data(controller.listKeys(TENANT).getBody()).get(0);
        assertThat(attributes(listed)).doesNotContainKey("id");

        String id = store.keys.get(0).id();
        Map<String, Object> single = resource(controller.retireKey(request, TENANT, "admin", id).getBody());
        assertThat(single.get("id")).isEqualTo(id);
        assertThat(attributes(single)).doesNotContainKey("id");
    }

    @Test
    @DisplayName("listing reports the platform key and whether signing is required")
    void listingReportsMeta() {
        Map<String, Object> body = controller.listKeys(TENANT).getBody();
        assertThat(body).containsKey("meta");
        Map<?, ?> meta = (Map<?, ?>) body.get("meta");
        assertThat(meta.get("signingRequired")).isEqualTo(false);
        assertThat(meta.get("platformKeyConfigured")).isEqualTo(false);
    }

    @Test
    @DisplayName("rejects a PEM that is not a usable key rather than storing it")
    void rejectsBadPem() {
        var response = controller.addKey(request, 
                TENANT, "admin", Map.of("label", "bad", "publicKeyPem", "not a pem"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(store.keys).isEmpty();
    }

    @Test
    @DisplayName("retiring a key with dependents warns instead of failing silently")
    void retireWarnsWhenModulesDependOnKey() throws Exception {
        controller.addKey(request, TENANT, "admin", Map.of("label", "2026-h1", "publicKeyPem", pem()));
        store.dependents = 3;

        Map<String, Object> single = resource(
                controller.retireKey(request, TENANT, "admin", store.keys.get(0).id()).getBody());

        assertThat(attributes(single).get("warning").toString())
                .contains("3 installed module(s)")
                .contains("stub handlers");
    }

    @Test
    @DisplayName("refuses to delete an active key — retire is the reversible step")
    void refusesDeletingActiveKey() throws Exception {
        controller.addKey(request, TENANT, "admin", Map.of("label", "2026-h2", "publicKeyPem", pem()));

        var response = controller.deleteKey(request, TENANT, store.keys.get(0).id());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(store.keys).hasSize(1);
    }

    @Test
    @DisplayName("refuses to delete a retired key that modules still depend on")
    void refusesDeletingKeyWithDependents() throws Exception {
        controller.addKey(request, TENANT, "admin", Map.of("label", "2026-h1", "publicKeyPem", pem()));
        String id = store.keys.get(0).id();
        controller.retireKey(request, TENANT, "admin", id);
        store.dependents = 2;

        var response = controller.deleteKey(request, TENANT, id);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(store.keys).hasSize(1);
    }

    @Test
    @DisplayName("another tenant's key id is a 404, not someone else's key")
    void keysAreTenantScoped() throws Exception {
        controller.addKey(request, TENANT, "admin", Map.of("label", "2026-h2", "publicKeyPem", pem()));
        String id = store.keys.get(0).id();

        assertThat(controller.retireKey(request, "tenant-b", "admin", id).getStatusCode().value())
                .isEqualTo(404);
        assertThat(controller.deleteKey(request, "tenant-b", id).getStatusCode().value()).isEqualTo(404);
    }

    /** In-memory store; setActive flips the stored row so the response reflects the change. */
    static final class FakeStore implements ModuleSigningKeyStore {
        final List<ModuleSigningKey> keys = new ArrayList<>();
        int dependents = 0;

        @Override
        public List<ModuleSigningKey> findActiveByTenant(String tenantId) {
            return keys.stream().filter(k -> k.tenantId().equals(tenantId))
                    .filter(ModuleSigningKey::active).toList();
        }

        @Override
        public List<ModuleSigningKey> findByTenant(String tenantId) {
            return keys.stream().filter(k -> k.tenantId().equals(tenantId)).toList();
        }

        @Override
        public Optional<ModuleSigningKey> findById(String tenantId, String id) {
            return keys.stream()
                    .filter(k -> k.tenantId().equals(tenantId) && k.id().equals(id)).findFirst();
        }

        @Override
        public String create(ModuleSigningKey key) {
            keys.add(key);
            return key.id();
        }

        @Override
        public boolean setActive(String tenantId, String id, boolean active, String updatedBy) {
            keys.replaceAll(k -> k.tenantId().equals(tenantId) && k.id().equals(id)
                    ? new ModuleSigningKey(k.id(), k.tenantId(), k.label(), k.algorithm(),
                        k.publicKeyPem(), k.fingerprint(), active,
                        active ? null : Instant.now(), k.createdAt(), k.createdBy())
                    : k);
            return true;
        }

        @Override
        public boolean delete(String tenantId, String id) {
            return keys.removeIf(k -> k.tenantId().equals(tenantId) && k.id().equals(id));
        }

        @Override
        public int countModulesSignedBy(String tenantId, String fingerprint) {
            return dependents;
        }

        @SuppressWarnings("unused")
        String newId() {
            return UUID.randomUUID().toString();
        }
    }
}
