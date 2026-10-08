package io.kelta.runtime.event;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Payload for record change events (create, update, delete).
 *
 * <p>Carried inside a {@link PlatformEvent} envelope and published to the
 * {@code kelta.record.changed} NATS subject, keyed by {@code tenantId:collectionName}.
 *
 * @since 1.0.0
 */
public class RecordChangedPayload {

    private String collectionName;
    private String recordId;
    private ChangeType changeType;
    private Map<String, Object> data;
    private Map<String, Object> previousData;
    private List<String> changedFields;

    /**
     * True when the record's collection has masking-configured fields. Broadcast
     * consumers that fan record data out to end users without per-user field
     * security (e.g. the gateway realtime bridge) must omit {@code data} /
     * {@code previousData} when set and let clients refetch through the
     * masked JSON:API path. Defaults to false; absent on events from older
     * pods (Jackson leaves it false).
     */
    private boolean containsMaskedFields;

    /**
     * True when the record's collection is owner-scoped (member data ownership): each record is
     * readable only by its owner, so broadcast consumers without per-subscriber row filtering
     * (the gateway realtime bridge) must publish invalidation-only events — no {@code data} —
     * and let clients refetch through the owner-scoped JSON:API path. Defaults to false.
     */
    private boolean ownerScoped;

    /**
     * Default constructor for deserialization.
     */
    public RecordChangedPayload() {
    }

    /**
     * Creates a new RecordChangedPayload with all fields.
     */
    public RecordChangedPayload(String collectionName, String recordId, ChangeType changeType,
                                 Map<String, Object> data, Map<String, Object> previousData,
                                 List<String> changedFields) {
        this.collectionName = collectionName;
        this.recordId = recordId;
        this.changeType = changeType;
        this.data = data;
        this.previousData = previousData;
        this.changedFields = changedFields;
    }

    /**
     * Creates a CREATED payload for a new record.
     */
    public static RecordChangedPayload created(String collectionName, String recordId,
                                                Map<String, Object> data) {
        return new RecordChangedPayload(collectionName, recordId, ChangeType.CREATED,
                data, null, List.of());
    }

    /**
     * Creates an UPDATED payload for a modified record.
     */
    public static RecordChangedPayload updated(String collectionName, String recordId,
                                                Map<String, Object> data,
                                                Map<String, Object> previousData,
                                                List<String> changedFields) {
        return new RecordChangedPayload(collectionName, recordId, ChangeType.UPDATED,
                data, previousData, changedFields);
    }

    /**
     * Creates a DELETED payload for a removed record.
     */
    public static RecordChangedPayload deleted(String collectionName, String recordId,
                                                Map<String, Object> data) {
        return new RecordChangedPayload(collectionName, recordId, ChangeType.DELETED,
                data, null, List.of());
    }

    // --- Getters and Setters ---

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }

    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public Map<String, Object> getPreviousData() { return previousData; }
    public void setPreviousData(Map<String, Object> previousData) { this.previousData = previousData; }

    public List<String> getChangedFields() { return changedFields; }
    public void setChangedFields(List<String> changedFields) { this.changedFields = changedFields; }

    public boolean isContainsMaskedFields() { return containsMaskedFields; }
    public void setContainsMaskedFields(boolean containsMaskedFields) { this.containsMaskedFields = containsMaskedFields; }

    /** Fluent variant of {@link #setContainsMaskedFields} for inline publish sites. */
    public RecordChangedPayload withContainsMaskedFields(boolean containsMaskedFields) {
        this.containsMaskedFields = containsMaskedFields;
        return this;
    }

    public boolean isOwnerScoped() { return ownerScoped; }
    public void setOwnerScoped(boolean ownerScoped) { this.ownerScoped = ownerScoped; }

    /** Fluent variant of {@link #setOwnerScoped} for inline publish sites. */
    public RecordChangedPayload withOwnerScoped(boolean ownerScoped) {
        this.ownerScoped = ownerScoped;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecordChangedPayload that = (RecordChangedPayload) o;
        return Objects.equals(collectionName, that.collectionName) &&
                Objects.equals(recordId, that.recordId) &&
                changeType == that.changeType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(collectionName, recordId, changeType);
    }

    @Override
    public String toString() {
        return "RecordChangedPayload{" +
                "collectionName='" + collectionName + '\'' +
                ", recordId='" + recordId + '\'' +
                ", changeType=" + changeType +
                ", changedFields=" + changedFields +
                '}';
    }
}
