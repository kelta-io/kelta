package io.kelta.worker.module;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleManifestParser;
import io.kelta.runtime.module.ModuleManifestParser.ModuleManifestException;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.worker.service.ModuleServicePrincipalService;
import io.kelta.worker.service.credential.CredentialDisabledException;
import io.kelta.worker.service.credential.CredentialNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Hands an external module the tenant credentials its approved manifest declares
 * ({@code permissions.credentials}), at {@code GET /api/modules/self/credentials/{name}}
 * ({@code specs/module-platform/9-external-modules.md} §3.3). Security-sensitive: it returns secret
 * material to an external service by design, so every check runs before anything is decrypted, in
 * this order, and any failure is a refusal:
 *
 * <ol>
 *   <li><b>The caller is a module's service principal</b> ({@link ModuleSelfPrincipal}). The token's {@code connected_app_id}
 *       (forwarded by the gateway as {@code X-Connected-App-Id}) names exactly one
 *       {@code tenant_module} row of the tenant, that row is external ({@code base_url}), the
 *       caller's resolved user is that row's {@code service_user_id}, and the app and user are
 *       still active ({@link ModuleServicePrincipalService#isActivePrincipal}). A user token, a PAT
 *       or a plain connected app carries no matching app id; another install's principal names
 *       another row. All of them are {@link Refusal#NOT_MODULE_PRINCIPAL}.</li>
 *   <li><b>The install is callable</b> ({@code ACTIVE} or {@code DEGRADED}, read from the row on
 *       every call, never from a pod's registry snapshot): a disabled, failed or still-installing
 *       module reads nothing ({@link Refusal#MODULE_NOT_ACTIVE}).</li>
 *   <li><b>The name is declared</b> in the stored, admin-approved manifest's
 *       {@code permissions.credentials} (exact match). A name shaped like a UUID is refused too: the
 *       resolver looks a UUID up as a credential <em>id</em>, and a module reads by name only
 *       ({@link Refusal#CREDENTIAL_NOT_DECLARED}).</li>
 * </ol>
 *
 * <p>Only then is the credential resolved, through {@link CredentialResolverPort} — so the read
 * writes a {@code CREDENTIAL_RESOLVE} row to {@code setup_audit_trail} whose {@code user_id} is the
 * module's SERVICE user and whose purpose is {@code MODULE:<moduleId>}, or
 * {@code MODULE:<moduleId>:<purpose>} when the module labels the read (the label is the module's,
 * the prefix is not; {@link #PURPOSE_LABEL}). Nothing here logs a secret or metadata value.
 */
public class ModuleCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ModuleCredentialService.class);

    /** What a module may add to the audit purpose: billing's {@code STRIPE_API:checkout}, for example. */
    public static final Pattern PURPOSE_LABEL = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    static final String PURPOSE_PREFIX = "MODULE:";

    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Why a read was refused. */
    public enum Refusal {
        /** The caller is not an external module install's active service principal. */
        NOT_MODULE_PRINCIPAL,
        /** The caller's install is not {@code ACTIVE} or {@code DEGRADED}. */
        MODULE_NOT_ACTIVE,
        /** The name is not in the installed manifest's {@code permissions.credentials}. */
        CREDENTIAL_NOT_DECLARED,
        /** Declared, but the tenant has no such credential, or it is inactive. */
        CREDENTIAL_NOT_FOUND,
        /** The platform has no credential vault configured ({@code KELTA_ENCRYPTION_KEY}). */
        CREDENTIALS_UNAVAILABLE
    }

    /** A refused read. The message names the reason and never a secret. */
    public static final class RefusedException extends RuntimeException {
        private final Refusal refusal;

        public RefusedException(Refusal refusal, String message) {
            super(message);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /**
     * A credential as a module receives it.
     *
     * @param moduleId the reading install's module id
     * @param name     the credential's name
     * @param type     its credential type ({@code stripe}, {@code api_key}, …)
     * @param secrets  the decrypted secret fields
     * @param metadata the non-secret companion fields (billing's {@code allowedReturnOrigins})
     */
    public record ModuleCredential(String moduleId, String name, String type, Map<String, Object> secrets,
                                   Map<String, Object> metadata) {
        /** Never prints the secrets or the metadata. */
        @Override
        public String toString() {
            return "ModuleCredential{moduleId=" + moduleId + ", name=" + name + ", type=" + type + '}';
        }
    }

    private final ModuleStore moduleStore;
    private final ModuleServicePrincipalService principals;
    private final ObjectProvider<CredentialResolverPort> credentialResolver;
    private final ModuleManifestParser parser;

    public ModuleCredentialService(ModuleStore moduleStore, ModuleServicePrincipalService principals,
                                   ObjectProvider<CredentialResolverPort> credentialResolver,
                                   ObjectMapper objectMapper) {
        this.moduleStore = Objects.requireNonNull(moduleStore);
        this.principals = Objects.requireNonNull(principals);
        this.credentialResolver = Objects.requireNonNull(credentialResolver);
        this.parser = new ModuleManifestParser(objectMapper);
    }

    /**
     * Reads credential {@code name} for the module whose principal is calling.
     *
     * @param tenantId       the request's tenant
     * @param connectedAppId the token's {@code connected_app_id}, or null
     * @param callerUserId   the caller's resolved {@code platform_user.id}, or null
     * @param name           the credential name from the path
     * @param purposeLabel   the module's optional audit label; already matched against {@link #PURPOSE_LABEL}
     * @throws RefusedException when any check fails, before anything is decrypted
     */
    public ModuleCredential read(String tenantId, String connectedAppId, String callerUserId, String name,
                                 String purposeLabel) {
        TenantModuleData install = ModuleSelfPrincipal.resolve(moduleStore, principals, tenantId, connectedAppId,
                callerUserId).orElse(null);
        if (install == null) {
            throw refuse(Refusal.NOT_MODULE_PRINCIPAL, tenantId, null, name,
                    "Only an external module's service principal can read module credentials");
        }
        if (!ExternalModuleInvoker.callable(install)) {
            throw refuse(Refusal.MODULE_NOT_ACTIVE, tenantId, install, name,
                    "Module '" + install.moduleId() + "' is " + install.status() + ", not active");
        }
        if (!declares(install, name) || UUID_SHAPE.matcher(name).matches()) {
            throw refuse(Refusal.CREDENTIAL_NOT_DECLARED, tenantId, install, name,
                    "Module '" + install.moduleId() + "' does not declare the credential '" + name + "'");
        }
        CredentialResolverPort resolver = credentialResolver.getIfAvailable();
        if (resolver == null) {
            throw refuse(Refusal.CREDENTIALS_UNAVAILABLE, tenantId, install, name,
                    "Credentials are not configured on this platform");
        }

        String purpose = PURPOSE_PREFIX + install.moduleId() + (purposeLabel == null ? "" : ":" + purposeLabel);
        ResolvedCredential credential;
        try {
            credential = resolver.resolve(tenantId, name, purpose, install.serviceUserId());
        } catch (CredentialNotFoundException | CredentialDisabledException e) {
            throw refuse(Refusal.CREDENTIAL_NOT_FOUND, tenantId, install, name,
                    "The tenant has no active credential named '" + name + "'");
        }
        log.debug("Module '{}' of tenant {} read credential '{}' ({})", install.moduleId(), tenantId, name, purpose);
        return new ModuleCredential(install.moduleId(), credential.name(), credential.type(),
                credential.secretFields(), credential.metadataFields());
    }

    /** Whether the stored, approved manifest lists {@code name}; an unreadable manifest declares nothing. */
    private boolean declares(TenantModuleData install, String name) {
        if (install.manifest() == null) {
            return false;
        }
        try {
            ExternalModuleManifest manifest = parser.parseExternal(install.manifest());
            return manifest.permissions().credentials().contains(name);
        } catch (ModuleManifestException e) {
            log.error("Installed manifest of module '{}' in tenant {} does not parse: {}",
                    install.moduleId(), install.tenantId(), e.getMessage());
            return false;
        }
    }

    private static RefusedException refuse(Refusal refusal, String tenantId, TenantModuleData install,
                                           String name, String message) {
        log.warn("Refused module credential read of '{}' in tenant {} ({}, module {})", name, tenantId, refusal,
                install == null ? "none" : install.moduleId());
        return new RefusedException(refusal, message);
    }
}
