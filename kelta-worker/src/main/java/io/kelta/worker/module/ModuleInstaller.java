package io.kelta.worker.module;

import io.kelta.runtime.module.ModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Installs a module for a tenant: the install-time signature gate, the JAR upload, the
 * {@code tenant_module} row and its action rows, and provisioning of the metadata the manifest
 * declares (with provenance).
 *
 * <p>Install never loads anything. Loading happens on enable or pod startup, in
 * {@link ModuleLoader}.
 */
final class ModuleInstaller {

    /**
     * Logs under {@link RuntimeModuleManager}'s name rather than this class's: this code was split
     * out of that class, and log queries and alerts select on the logger name.
     */
    private static final Logger log = LoggerFactory.getLogger(RuntimeModuleManager.class);

    private final ModuleStore moduleStore;
    private final ModuleManifestParser manifestParser;
    private final ModuleJarService jarService;
    private final ModuleSignatureVerifier signatureVerifier;
    private final ModuleMetadataProvisioner metadataProvisioner;

    /** Records what each module provisioned. Null in tests that do not exercise provisioning. */
    private ModuleProvenanceStore provenanceStore;

    ModuleInstaller(ModuleStore moduleStore, ModuleManifestParser manifestParser,
                    ModuleJarService jarService, ModuleSignatureVerifier signatureVerifier,
                    ModuleMetadataProvisioner metadataProvisioner) {
        this.moduleStore = moduleStore;
        this.manifestParser = manifestParser;
        this.jarService = jarService;
        this.signatureVerifier = signatureVerifier;
        this.metadataProvisioner = metadataProvisioner;
    }

    /**
     * Installs a module for a tenant.
     * Parses the manifest, persists metadata, and creates action records.
     *
     * @param tenantId    the tenant ID
     * @param manifestJson the module manifest JSON
     * @param sourceUrl   the original download URL
     * @param checksum    SHA-256 checksum
     * @param jarSizeBytes JAR file size
     * @param installedBy the user who installed the module
     * @return the persisted module data
     */
    public TenantModuleData installModule(String tenantId, String manifestJson,
                                           String sourceUrl, String checksum,
                                           Long jarSizeBytes, String installedBy) {
        ModuleManifest manifest = manifestParser.parse(manifestJson);

        // Check for existing installation
        Optional<TenantModuleData> existing = moduleStore.findByTenantAndModuleId(
            tenantId, manifest.id());
        if (existing.isPresent()) {
            throw new IllegalStateException(
                "Module '" + manifest.id() + "' is already installed for tenant " + tenantId);
        }

        String id = UUID.randomUUID().toString();
        TenantModuleData data = new TenantModuleData(
            id, tenantId, manifest.id(), manifest.name(), manifest.version(),
            manifest.description(), sourceUrl, checksum, jarSizeBytes,
            manifest.moduleClass(), manifestJson,
            TenantModuleData.STATUS_INSTALLED, installedBy,
            null, null, null, List.of()
        );

        moduleStore.createModule(data);

        // Create action records from manifest
        List<TenantModuleData.TenantModuleActionData> actions = new ArrayList<>();
        for (ModuleManifest.ActionHandlerManifest handler : manifest.actionHandlers()) {
            actions.add(new TenantModuleData.TenantModuleActionData(
                UUID.randomUUID().toString(), id, handler.key(), handler.name(),
                handler.category(), handler.description(), handler.configSchema(),
                handler.inputSchema(), handler.outputSchema()
            ));
        }
        if (!actions.isEmpty()) {
            moduleStore.createActions(actions);
        }

        provisionCollections(tenantId, manifest, id, installedBy);

        log.info("Installed module '{}' v{} for tenant {} with {} action handlers",
            manifest.name(), manifest.version(), tenantId, actions.size());

        return moduleStore.findById(id).orElse(data);
    }

    /**
     * Installs a module with its JAR file, verifying the JAR's publisher signature
     * first (Rec 9). When signature verification is enabled
     * ({@code kelta.modules.signing.public-key} set), {@code signatureBase64} must
     * be a valid detached signature over the JAR bytes or the install is rejected
     * before anything is uploaded or persisted.
     *
     * @param signatureBase64 detached base64 signature over the JAR bytes (may be
     *                        {@code null} when verification is disabled)
     * @throws ModuleSignatureException if signature verification fails
     */
    public TenantModuleData installModuleWithJar(String tenantId, String manifestJson,
                                                   byte[] jarBytes, String installedBy,
                                                   String signatureBase64) {
        if (jarService == null) {
            throw new IllegalStateException("JAR upload requires S3 storage to be enabled");
        }

        // Authenticity gate — reject before any S3 upload or DB write. Verified against the
        // INSTALLING TENANT's own signing keys, so a JAR signed for one tenant cannot be
        // installed into another. Null when the tenant trusts no key and signing is not
        // required; a ModuleSignatureException otherwise.
        String signingKeyFingerprint = null;
        if (signatureVerifier != null) {
            signingKeyFingerprint = signatureVerifier.verify(tenantId, jarBytes, signatureBase64);
        }

        ModuleManifest manifest = manifestParser.parse(manifestJson);

        // Duplicate check BEFORE the upload. The other order leaves an orphan JAR in S3 on every
        // rejected install: uninstall only ever deletes the s3Key recorded on the module row, and
        // a rejected install never wrote one.
        Optional<TenantModuleData> existing = moduleStore.findByTenantAndModuleId(
            tenantId, manifest.id());
        if (existing.isPresent()) {
            throw new IllegalStateException(
                "Module '" + manifest.id() + "' is already installed for tenant " + tenantId);
        }

        String checksum = ModuleJarService.sha256(jarBytes);
        String s3Key = jarService.uploadJar(tenantId, manifest.id(), manifest.version(), jarBytes);

        String id = UUID.randomUUID().toString();
        TenantModuleData data = new TenantModuleData(
            id, tenantId, manifest.id(), manifest.name(), manifest.version(),
            manifest.description(), s3Key, checksum, (long) jarBytes.length,
            manifest.moduleClass(), manifestJson,
            TenantModuleData.STATUS_INSTALLED, installedBy,
            null, null, s3Key, List.of()
        );

        moduleStore.createModule(data);
        if (signatureBase64 != null && !signatureBase64.isBlank()) {
            // Keep the verified signature so every subsequent load can re-verify
            // the downloaded JAR (defense-in-depth vs S3 tamper), and the key that
            // verified it so a rotation can tell what still depends on that key.
            moduleStore.saveJarSignature(id, signatureBase64, signingKeyFingerprint);
        }

        // Create action records from manifest
        List<TenantModuleData.TenantModuleActionData> actions = new ArrayList<>();
        for (ModuleManifest.ActionHandlerManifest handler : manifest.actionHandlers()) {
            actions.add(new TenantModuleData.TenantModuleActionData(
                UUID.randomUUID().toString(), id, handler.key(), handler.name(),
                handler.category(), handler.description(), handler.configSchema(),
                handler.inputSchema(), handler.outputSchema()
            ));
        }
        if (!actions.isEmpty()) {
            moduleStore.createActions(actions);
        }

        provisionCollections(tenantId, manifest, id, installedBy);

        log.info("Installed module '{}' v{} for tenant {} with JAR (s3Key={}, {} bytes)",
            manifest.name(), manifest.version(), tenantId, s3Key, jarBytes.length);

        return moduleStore.findById(id).orElse(data);
    }

    /**
     * Provisions the metadata the manifest declares, in the installing tenant, and records what it
     * created versus adopted.
     *
     * <p>Failure is still not thrown: the module row and its action records are already persisted
     * at this point, so propagating would leave a half-installed module the admin cannot see or
     * uninstall. The module is marked {@code FAILED} instead — on a thrown error or on any failed
     * import item — which is the state an admin can actually see and act on. Provenance is still
     * recorded for the items that succeeded, so a retry finds its own half-created collection
     * already recorded as {@code CREATED}.
     *
     * @param moduleRowId the {@code tenant_module} row, so a failure can be recorded against it
     */
    private void provisionCollections(String tenantId, ModuleManifest manifest, String moduleRowId,
                                      String installedBy) {
        if (metadataProvisioner == null || manifest.collections().isEmpty()) {
            return;
        }
        try {
            var result = metadataProvisioner.provision(tenantId, manifest, installedBy);
            recordProvenance(tenantId, manifest, result);
            if (result.hasFailures()) {
                log.error("Module '{}' failed to provision {} item(s) for tenant {} and is marked "
                        + "FAILED: {}", manifest.id(), result.failures().size(), tenantId,
                    result.failures());
                markFailed(moduleStore, moduleRowId);
                return;
            }
            log.info("Module '{}' provisioned {} resource(s) for tenant {}",
                manifest.id(), result.resources().size(), tenantId);
        } catch (RuntimeException e) {
            log.error("Module '{}' failed to provision collections for tenant {} "
                    + "and is marked FAILED: {}",
                manifest.id(), tenantId, e.getMessage(), e);
            markFailed(moduleStore, moduleRowId);
        }
    }

    static void markFailed(ModuleStore moduleStore, String moduleRowId) {
        try {
            moduleStore.updateStatus(moduleRowId, TenantModuleData.STATUS_FAILED);
        } catch (RuntimeException ignored) {
            // Bookkeeping must not mask the provisioning error.
        }
    }

    /**
     * Records one provenance row per resource the run created or adopted.
     *
     * <p>Best-effort: losing the bookkeeping must not fail an install that otherwise succeeded. The
     * cost of a missing record is a conservative uninstall, which is the behaviour today anyway.
     */
    private void recordProvenance(String tenantId, ModuleManifest manifest,
                                  ModuleMetadataProvisioner.ProvisionResult result) {
        if (provenanceStore == null) {
            return;
        }
        try {
            for (var resource : result.resources()) {
                provenanceStore.record(tenantId, manifest.id(), manifest.version(),
                    resource.type(), resource.naturalKey(), resource.resourceId(),
                    resource.ownership(), resource.contentHash());
            }
        } catch (RuntimeException e) {
            log.warn("Could not record provenance for module '{}' in tenant {}: {}",
                manifest.id(), tenantId, e.getMessage());
        }
    }

    /** Injected via setter so the several existing constructor overloads stay as they are. */
    public void setProvenanceStore(ModuleProvenanceStore provenanceStore) {
        this.provenanceStore = provenanceStore;
    }
}
