package io.kelta.runtime.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload for module lifecycle change events published to NATS.
 * Propagates module install/upgrade/enable/disable/uninstall events to all worker pods, each of
 * which re-reads the install row on receipt.
 *
 * <p>Ignores unknown properties: before slice 9g the payload also carried the JAR module's
 * {@code s3Key} and {@code moduleClass}, and a pod of the previous release may still publish them
 * during a rolling deploy.
 *
 * @since 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModuleChangedPayload {

    private String id;
    private String tenantId;
    private String moduleId;
    private String name;
    private String version;
    private String manifest;
    private ModuleChangeType changeType;

    public ModuleChangedPayload() {
    }

    public ModuleChangedPayload(String id, String tenantId, String moduleId, String name,
                                 String version, String manifest, ModuleChangeType changeType) {
        this.id = id;
        this.tenantId = tenantId;
        this.moduleId = moduleId;
        this.name = name;
        this.version = version;
        this.manifest = manifest;
        this.changeType = changeType;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getModuleId() { return moduleId; }
    public void setModuleId(String moduleId) { this.moduleId = moduleId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getManifest() { return manifest; }
    public void setManifest(String manifest) { this.manifest = manifest; }

    public ModuleChangeType getChangeType() { return changeType; }
    public void setChangeType(ModuleChangeType changeType) { this.changeType = changeType; }

    @Override
    public String toString() {
        return "ModuleChangedPayload{" +
            "tenantId='" + tenantId + '\'' +
            ", moduleId='" + moduleId + '\'' +
            ", name='" + name + '\'' +
            ", version='" + version + '\'' +
            ", changeType=" + changeType +
            '}';
    }
}
