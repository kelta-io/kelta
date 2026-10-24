package io.kelta.runtime.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Payload for collection changed events.
 * Contains the full collection entity data for consumers.
 * 
 * This is a shared event payload used across all Kelta services.
 */
public class CollectionChangedPayload {

    private String id;
    private String name;
    private String displayName;
    private String description;
    /**
     * {@code null} when the publisher does not know the collection's state (field, rule
     * and layout hooks reuse this payload); consumers must not read a missing flag as false.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean active;
    private Integer currentVersion;
    private List<FieldPayload> fields;
    private Instant createdAt;
    private Instant updatedAt;
    private ChangeType changeType;

    /**
     * Default constructor for deserialization.
     */
    public CollectionChangedPayload() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Integer currentVersion) {
        this.currentVersion = currentVersion;
    }

    public List<FieldPayload> getFields() {
        return fields;
    }

    public void setFields(List<FieldPayload> fields) {
        this.fields = fields;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CollectionChangedPayload that = (CollectionChangedPayload) o;
        return Objects.equals(active, that.active) &&
                Objects.equals(id, that.id) &&
                Objects.equals(name, that.name) &&
                Objects.equals(description, that.description) &&
                Objects.equals(currentVersion, that.currentVersion) &&
                changeType == that.changeType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, active, currentVersion, changeType);
    }

    @Override
    public String toString() {
        return "CollectionChangedPayload{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", changeType=" + changeType +
                ", currentVersion=" + currentVersion +
                ", active=" + active +
                '}';
    }

    /**
     * Nested class for field data in the payload.
     */
    public static class FieldPayload {
        private String id;
        private String name;
        private String type;
        private boolean required;
        private boolean unique;
        private String description;
        private String constraints;
        private String fieldTypeConfig;
        private String referenceTarget;
        private String relationshipType;
        private String relationshipName;
        private boolean cascadeDelete;

        public FieldPayload() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public boolean isUnique() {
            return unique;
        }

        public void setUnique(boolean unique) {
            this.unique = unique;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getConstraints() {
            return constraints;
        }

        public void setConstraints(String constraints) {
            this.constraints = constraints;
        }

        public String getFieldTypeConfig() {
            return fieldTypeConfig;
        }

        public void setFieldTypeConfig(String fieldTypeConfig) {
            this.fieldTypeConfig = fieldTypeConfig;
        }

        public String getReferenceTarget() {
            return referenceTarget;
        }

        public void setReferenceTarget(String referenceTarget) {
            this.referenceTarget = referenceTarget;
        }

        public String getRelationshipType() {
            return relationshipType;
        }

        public void setRelationshipType(String relationshipType) {
            this.relationshipType = relationshipType;
        }

        public String getRelationshipName() {
            return relationshipName;
        }

        public void setRelationshipName(String relationshipName) {
            this.relationshipName = relationshipName;
        }

        public boolean isCascadeDelete() {
            return cascadeDelete;
        }

        public void setCascadeDelete(boolean cascadeDelete) {
            this.cascadeDelete = cascadeDelete;
        }
    }
}
