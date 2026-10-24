package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.module.ModuleSigningKey;
import io.kelta.runtime.module.ModuleSigningKeyStore;
import io.kelta.worker.interceptor.PermissionGatedController;
import io.kelta.worker.module.ModuleSignatureVerifier;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Management of a tenant's module JAR signing keys — the trust anchors a module JAR signature is
 * checked against before the JAR is allowed to run arbitrary Java in the worker JVM.
 *
 * <p>Several keys may be active at once, and a signature is accepted if it matches any of them.
 * The rotation that buys is:
 * <ol>
 *   <li>{@code POST /api/modules/signing-keys} — add the new key; both are now trusted</li>
 *   <li>re-sign and re-install modules, checking {@code dependentModules} as it drains</li>
 *   <li>{@code POST /api/modules/signing-keys/{id}/retire} — once nothing depends on the old key</li>
 * </ol>
 *
 * <p>Only public keys pass through here, so responses may safely include the PEM. Adding a key is
 * nonetheless a grant of code-execution authority over the tenant, which is why writes on this
 * path require {@code MANAGE_CREDENTIALS} — at the gateway and again here — rather than the
 * blanket {@code API_ACCESS} the rest of {@code /api/modules/**} carries. The key list stays at
 * {@code API_ACCESS}: the keys are public, and {@code dependentModules} is what makes a rotation
 * safe to plan.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/modules/signing-keys")
@ConditionalOnBean(ModuleSigningKeyStore.class)
public class ModuleSigningKeyController implements PermissionGatedController {

    private static final Logger log = LoggerFactory.getLogger(ModuleSigningKeyController.class);

    private static final int MAX_LABEL_LENGTH = 100;

    /** The gateway's rule for writes under {@code /api/modules/signing-keys}, repeated here. */
    static final String WRITE_PERMISSION = "MANAGE_CREDENTIALS";

    private final ModuleSigningKeyStore keyStore;
    private final ModuleSignatureVerifier signatureVerifier;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public ModuleSigningKeyController(ModuleSigningKeyStore keyStore,
                                       ModuleSignatureVerifier signatureVerifier,
                                       CerbosPermissionResolver permissionResolver,
                                       BootstrapRepository bootstrapRepository) {
        this.keyStore = keyStore;
        this.signatureVerifier = signatureVerifier;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    /** Gates key writes on {@value #WRITE_PERMISSION}. Fail-closed: no resolvable profile is a 403. */
    private void requireWritePermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> WRITE_PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, WRITE_PERMISSION + " permission required");
        }
    }

    /**
     * Lists the tenant's signing keys, retired ones included.
     *
     * <p>Each entry carries {@code dependentModules}: how many installed modules were signed by
     * that key. Retiring a key with a non-zero count makes those modules fail verification on
     * their next load and fall back to inert stub handlers, while {@code /api/modules} continues
     * to report them {@code ACTIVE} — so this count is the pre-flight check for a rotation, not a
     * statistic.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listKeys(
            @RequestHeader("X-Tenant-ID") String tenantId) {
        List<Map<String, Object>> records = keyStore.findByTenant(tenantId).stream()
                .map(key -> keyToMap(key, keyStore.countModulesSignedBy(tenantId, key.fingerprint())))
                .toList();

        // Surfaced because it changes what these keys mean: while a platform-wide key is
        // configured it is trusted for every tenant, so this tenant's key set is not the whole
        // trust boundary. Its fingerprint is included so a module recorded against it is
        // identifiable rather than looking like an unknown key.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("signingRequired", signatureVerifier.isRequired());
        String platformFingerprint = signatureVerifier.platformKeyFingerprint();
        meta.put("platformKeyConfigured", platformFingerprint != null);
        if (platformFingerprint != null) {
            meta.put("platformKeyFingerprint", platformFingerprint);
        }
        return ResponseEntity.ok(
                JsonApiResponseBuilder.collection("module-signing-keys", records, meta));
    }

    /**
     * Adds a trust anchor for this tenant.
     *
     * @param body {@code label}, {@code publicKeyPem}, and optional {@code algorithm}
     *             (default {@code Ed25519})
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addKey(
            HttpServletRequest request,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestBody Map<String, Object> body) {
        requireWritePermission(request);

        String label = string(body.get("label"));
        String publicKeyPem = string(body.get("publicKeyPem"));
        String algorithm = string(body.get("algorithm"));
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = "Ed25519";
        }

        if (label == null || label.isBlank()) {
            return badRequest("label is required");
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            return badRequest("label must be at most " + MAX_LABEL_LENGTH + " characters");
        }
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            return badRequest("publicKeyPem is required");
        }

        // Parse and fingerprint before persisting: a key that cannot be parsed would sit in the
        // trust set verifying nothing, and be skipped with a warning on every single install.
        String fingerprint;
        try {
            fingerprint = ModuleSignatureVerifier.fingerprint(publicKeyPem, algorithm);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        ModuleSigningKey key = new ModuleSigningKey(
                UUID.randomUUID().toString(), tenantId, label.trim(), algorithm.trim(),
                publicKeyPem.trim(), fingerprint, true, null, null,
                userId != null ? userId : "system");

        try {
            keyStore.create(key);
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(409).body(JsonApiResponseBuilder.error("409", "Conflict",
                    "This tenant already has a signing key with that label or public key"));
        }

        log.info("Added module signing key '{}' ({}, fingerprint {}) for tenant {}",
                key.label(), key.algorithm(), key.shortFingerprint(), tenantId);
        return singleKey(key, 0, null);
    }

    /**
     * Retires a key: signatures from it stop being accepted, the row stays.
     *
     * <p>Any module still recorded against it will fail verification on its next load and fall
     * back to stub handlers. The response reports that count so the consequence is visible at the
     * moment of the change rather than at the next pod restart.
     */
    @PostMapping("/{id}/retire")
    public ResponseEntity<Map<String, Object>> retireKey(
            HttpServletRequest request,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @PathVariable String id) {
        requireWritePermission(request);
        return setActive(tenantId, userId, id, false);
    }

    /**
     * Re-activates a retired key — the rollback for a rotation retired too early.
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activateKey(
            HttpServletRequest request,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @PathVariable String id) {
        requireWritePermission(request);
        return setActive(tenantId, userId, id, true);
    }

    /**
     * Permanently deletes a retired key.
     *
     * <p>Refused while the key is active. With signing required, a tenant's last key going away is
     * the difference between installs being blocked and installs being unverified, and that should
     * not be reachable in one call — retire first, which is reversible and leaves the fingerprint
     * on affected modules resolvable.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteKey(
            HttpServletRequest request,
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String id) {
        requireWritePermission(request);
        ModuleSigningKey key = keyStore.findById(tenantId, id).orElse(null);
        if (key == null) {
            return notFound();
        }
        if (key.active()) {
            return ResponseEntity.status(409).body(JsonApiResponseBuilder.error("409", "Conflict",
                    "Retire the key before deleting it"));
        }
        int dependents = keyStore.countModulesSignedBy(tenantId, key.fingerprint());
        if (dependents > 0) {
            return ResponseEntity.status(409).body(JsonApiResponseBuilder.error("409", "Conflict",
                    dependents + " installed module(s) are recorded against this key — deleting it "
                    + "would leave them unexplainable. Re-sign and re-install them first"));
        }
        keyStore.delete(tenantId, id);
        log.info("Deleted retired module signing key '{}' for tenant {}", key.label(), tenantId);
        return singleKey(key, 0, null);
    }

    private ResponseEntity<Map<String, Object>> setActive(
            String tenantId, String userId, String id, boolean active) {
        ModuleSigningKey existing = keyStore.findById(tenantId, id).orElse(null);
        if (existing == null) {
            return notFound();
        }
        keyStore.setActive(tenantId, id, active, userId != null ? userId : "system");

        int dependents = keyStore.countModulesSignedBy(tenantId, existing.fingerprint());
        if (!active && dependents > 0) {
            log.warn("Retired module signing key '{}' for tenant {} while {} installed module(s) "
                    + "still depend on it — they will load as stub handlers until re-signed",
                    existing.label(), tenantId, dependents);
        } else {
            log.info("Set module signing key '{}' active={} for tenant {}",
                    existing.label(), active, tenantId);
        }

        ModuleSigningKey updated = keyStore.findById(tenantId, id).orElse(existing);
        String warning = (!active && dependents > 0)
                ? dependents + " installed module(s) were signed by this key and will fall back "
                    + "to stub handlers on their next load until re-signed with an active key"
                : null;
        return singleKey(updated, dependents, warning);
    }

    /**
     * One key as a JSON:API single-resource response.
     *
     * <p>Drops {@code id} from the attribute map — {@code single()} puts the id in the resource
     * envelope itself and does not strip it from attributes the way the collection path does, and
     * JSON:API forbids an {@code id} member inside {@code attributes}.
     */
    private ResponseEntity<Map<String, Object>> singleKey(
            ModuleSigningKey key, int dependentModules, String warning) {
        Map<String, Object> attributes = keyToMap(key, dependentModules);
        attributes.remove("id");
        if (warning != null) {
            attributes.put("warning", warning);
        }
        return ResponseEntity.ok(JsonApiResponseBuilder.single(
                "module-signing-keys", key.id(), attributes));
    }

    private Map<String, Object> keyToMap(ModuleSigningKey key, int dependentModules) {
        Map<String, Object> map = new LinkedHashMap<>();
        // JsonApiResponseBuilder.toResource() lifts "id" out of the record map into the resource
        // envelope. Without it every listed key serialises as "id": null, and since retire,
        // activate and delete are all addressed by id, the listing could not be acted on — which
        // is the entire rotation workflow.
        map.put("id", key.id());
        map.put("label", key.label());
        map.put("algorithm", key.algorithm());
        map.put("fingerprint", key.fingerprint());
        map.put("publicKeyPem", key.publicKeyPem());
        map.put("active", key.active());
        map.put("dependentModules", dependentModules);
        map.put("retiredAt", key.retiredAt());
        map.put("createdAt", key.createdAt());
        map.put("createdBy", key.createdBy());
        return map;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(
                JsonApiResponseBuilder.error("400", "Validation Error", detail));
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(
                JsonApiResponseBuilder.error("404", "Not Found", "Signing key not found"));
    }
}
