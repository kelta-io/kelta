package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges duplicate records: keeps a chosen master, re-parents every child that references a
 * duplicate onto the master, then deletes the duplicates.
 *
 * <p>The write path is the follow-up to the read-only {@link DuplicateDetectionService}. Like
 * detection, it goes exclusively through the authorized {@link QueryEngine} — which validates
 * field names, resolves the per-tenant schema, enforces RLS, and fires validation, before-save
 * hooks and {@code record.*} events — rather than issuing raw SQL against the dynamic user tables.
 *
 * <p>Ordering matters and is deliberate:
 * <ol>
 *   <li><b>Apply overrides</b> to the master (a normal {@code update}, so validation/hooks fire).</li>
 *   <li><b>Re-parent</b> inbound FKs from each duplicate to the master. This runs <em>before</em>
 *       the delete so that {@code MASTER_DETAIL} children — which are {@code ON DELETE CASCADE} —
 *       are moved off the duplicate and are not cascade-deleted with it.</li>
 *   <li><b>Delete</b> the duplicate records.</li>
 * </ol>
 *
 * <p>An inbound field its own collection declares immutable cannot be re-parented — an update
 * naming it is rejected, not applied. Those relationships are skipped and returned in
 * {@link Result#skippedImmutable()} rather than counted as moved.
 *
 * <p>The whole operation is expected to run inside the caller's transaction so a mid-merge failure
 * rolls back cleanly (see {@code RecordMergeController}).
 *
 * @since 1.0.0
 */
@Service
public class RecordMergeService {

    /** Page size for the bounded child scan (the query engine caps a single page at 1000). */
    private static final int PAGE_SIZE = 1000;

    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;

    public RecordMergeService(QueryEngine queryEngine, CollectionRegistry collectionRegistry) {
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
    }

    /** How many child records were re-parented for a single inbound relationship field. */
    public record ReparentedField(String collection, String field, int count) {
    }

    /** An inbound relationship the merge could not re-parent because the field is immutable. */
    public record SkippedField(String collection, String field) {
    }

    /** Merge outcome: the surviving master, what was deleted, and the re-parenting breakdown. */
    public record Result(String masterId,
                         List<String> deletedIds,
                         int reparentedRecords,
                         List<ReparentedField> reparented,
                         List<SkippedField> skippedImmutable) {
    }

    /** A field in another collection whose LOOKUP/MASTER_DETAIL relationship targets the merged collection. */
    private record InboundRef(CollectionDefinition definition, String fieldName) {
    }

    /**
     * Merges {@code duplicateIds} into {@code masterId} within {@code collectionName}.
     *
     * @param collectionName the collection whose records are being merged
     * @param masterId       the record to keep (must exist; must not appear in {@code duplicateIds})
     * @param duplicateIds   the records to fold into the master (non-empty; each must exist)
     * @param fieldOverrides optional per-field values applied to the master before the merge
     * @return the merge result (surviving master, deleted ids, re-parenting breakdown)
     * @throws IllegalArgumentException if the collection is unknown, ids are missing/empty, the
     *                                  master is listed as a duplicate, or any record does not exist
     */
    public Result merge(String collectionName,
                        String masterId,
                        List<String> duplicateIds,
                        Map<String, Object> fieldOverrides) {
        if (masterId == null || masterId.isBlank()) {
            throw new IllegalArgumentException("masterId is required");
        }
        if (duplicateIds == null || duplicateIds.isEmpty()) {
            throw new IllegalArgumentException("duplicateIds must be non-empty");
        }
        // De-dup the input and reject the master appearing among the duplicates (would delete the survivor).
        Set<String> dupes = new LinkedHashSet<>();
        for (String id : duplicateIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("duplicateIds must not contain blank values");
            }
            if (id.equals(masterId)) {
                throw new IllegalArgumentException("masterId must not be listed in duplicateIds");
            }
            dupes.add(id);
        }

        CollectionDefinition def = collectionRegistry.get(collectionName);
        if (def == null) {
            throw new IllegalArgumentException("Collection not found: " + collectionName);
        }

        // Every record must exist up front, so a bad id fails the whole merge before any write.
        if (queryEngine.getById(def, masterId).isEmpty()) {
            throw new IllegalArgumentException("Master record not found: " + masterId);
        }
        for (String dupId : dupes) {
            if (queryEngine.getById(def, dupId).isEmpty()) {
                throw new IllegalArgumentException("Duplicate record not found: " + dupId);
            }
        }

        // 1. Apply optional overrides to the master via the normal write path.
        if (fieldOverrides != null && !fieldOverrides.isEmpty()) {
            queryEngine.update(def, masterId, fieldOverrides);
        }

        // 2. Re-parent inbound FKs from each duplicate onto the master (before delete — see class doc).
        List<InboundRef> inbound = findInboundReferences(collectionName);
        List<ReparentedField> reparented = new ArrayList<>();
        List<SkippedField> skippedImmutable = new ArrayList<>();
        int totalReparented = 0;
        for (InboundRef ref : inbound) {
            // A field the owning collection declares immutable cannot be re-pointed by an
            // update. That was always true — the write used to be dropped silently and
            // still counted as re-parented (#1330). Report it instead of miscounting; the
            // caller has to fix those children another way before deleting the duplicate.
            if (ref.definition().immutableFields().contains(ref.fieldName())) {
                skippedImmutable.add(new SkippedField(ref.definition().name(), ref.fieldName()));
                continue;
            }
            int fieldCount = 0;
            for (String dupId : dupes) {
                fieldCount += reparent(ref, dupId, masterId);
            }
            if (fieldCount > 0) {
                reparented.add(new ReparentedField(ref.definition().name(), ref.fieldName(), fieldCount));
                totalReparented += fieldCount;
            }
        }

        // 3. Delete the duplicates via the normal write path.
        List<String> deleted = new ArrayList<>();
        for (String dupId : dupes) {
            if (queryEngine.delete(def, dupId)) {
                deleted.add(dupId);
            }
        }

        return new Result(masterId, deleted, totalReparented, reparented, skippedImmutable);
    }

    /**
     * Finds every field, in any collection, whose LOOKUP/MASTER_DETAIL relationship targets
     * {@code targetCollection}. These are the inbound FKs that must be re-parented on a merge.
     */
    private List<InboundRef> findInboundReferences(String targetCollection) {
        List<InboundRef> refs = new ArrayList<>();
        for (CollectionDefinition def : collectionRegistry.getAllForCurrentTenant()) {
            for (FieldDefinition field : def.fields()) {
                if ((field.type() == FieldType.LOOKUP || field.type() == FieldType.MASTER_DETAIL)
                        && field.referenceConfig() != null
                        && isTarget(field.referenceConfig(), targetCollection)) {
                    refs.add(new InboundRef(def, field.name()));
                }
            }
        }
        return refs;
    }

    private static boolean isTarget(ReferenceConfig config, String targetCollection) {
        return targetCollection.equals(config.targetCollection());
    }

    /**
     * Re-points every child of {@code ref.definition()} whose FK equals {@code fromId} to
     * {@code toId}, via the normal write path. Always scans page 1: each update removes a row from
     * the {@code field == fromId} filter, so the next page-1 read returns the still-unmigrated set
     * until the filter is empty — no offset walking, no skipped rows.
     *
     * @return the number of child records re-parented
     */
    private int reparent(InboundRef ref, String fromId, String toId) {
        int updated = 0;
        while (true) {
            QueryRequest request = new QueryRequest(
                    new Pagination(1, PAGE_SIZE),
                    List.of(),
                    List.of("id", ref.fieldName()),
                    List.of(new FilterCondition(ref.fieldName(), FilterOperator.EQ, fromId)));
            QueryResult result = queryEngine.executeQuery(ref.definition(), request);
            List<Map<String, Object>> rows = result.data();
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                Object id = row.get("id");
                if (id == null) {
                    continue;
                }
                queryEngine.update(ref.definition(), String.valueOf(id), Map.of(ref.fieldName(), toId));
                updated++;
            }
            // Fewer than a full page means the filter is now drained.
            if (rows.size() < PAGE_SIZE) {
                break;
            }
        }
        return updated;
    }
}
