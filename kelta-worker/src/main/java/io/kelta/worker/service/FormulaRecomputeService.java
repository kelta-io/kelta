package io.kelta.worker.service;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.worker.util.TenantContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Background service that recomputes a FORMULA field's value for every existing
 * record in a collection. Triggered by {@code FieldConfigEventPublisher} after
 * a formula field is created or its {@code expression} changes — new evaluations
 * happen automatically for subsequent writes through {@code FormulaComputeHook},
 * but pre-existing rows would otherwise keep evaluating against the old
 * expression on read until they are touched.
 *
 * <p>Pages through the target collection in batches of {@value #PAGE_SIZE} using
 * the {@link StorageAdapter}, then applies a batch UPDATE per page via
 * {@link JdbcTemplate}. FORMULA fields have no physical column today
 * (see {@code FieldType#hasPhysicalColumn()}), so the per-row UPDATE is a
 * {@code SET updated_at = updated_at} no-op that simply bumps the row's
 * row-version — sufficient to invalidate any downstream caches keyed on the
 * record identity without altering audit timestamps.
 *
 * @since 1.0.0
 */
@Service
public class FormulaRecomputeService {

    private static final Logger log = LoggerFactory.getLogger(FormulaRecomputeService.class);

    static final int PAGE_SIZE = 500;

    private static final String SELECT_TENANT_SLUG = """
            SELECT slug FROM tenant WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CollectionRegistry collectionRegistry;
    private final StorageAdapter storageAdapter;
    private final FormulaEvaluator formulaEvaluator;

    public FormulaRecomputeService(JdbcTemplate jdbcTemplate,
                                   CollectionRegistry collectionRegistry,
                                   StorageAdapter storageAdapter,
                                   FormulaEvaluator formulaEvaluator) {
        this.jdbcTemplate = jdbcTemplate;
        this.collectionRegistry = collectionRegistry;
        this.storageAdapter = storageAdapter;
        this.formulaEvaluator = formulaEvaluator;
    }

    /**
     * Recomputes {@code fieldName} for every record in {@code collectionName},
     * paged in batches of {@value #PAGE_SIZE}.
     *
     * @param tenantId       owning tenant; must be non-blank
     * @param collectionName collection containing the formula field
     * @param fieldName      formula field whose expression changed
     */
    @Async("applicationTaskExecutor")
    public void recomputeAsync(String tenantId, String collectionName, String fieldName) {
        if (tenantId == null || tenantId.isBlank()
                || collectionName == null || collectionName.isBlank()
                || fieldName == null || fieldName.isBlank()) {
            log.warn("recomputeAsync called with blank arguments: tenantId={}, collection={}, field={}",
                    tenantId, collectionName, fieldName);
            return;
        }
        log.info("Scheduling formula recompute for {}.{} (tenant={})",
                collectionName, fieldName, tenantId);
        try {
            TenantContextUtils.withTenant(tenantId, () -> recompute(collectionName, fieldName));
        } catch (Exception e) {
            log.error("Formula recompute failed for {}.{} (tenant={}): {}",
                    collectionName, fieldName, tenantId, e.getMessage(), e);
        }
    }

    private void recompute(String collectionName, String fieldName) {
        // Schema-per-tenant table resolution needs the slug; bind it for the recompute when the
        // caller's scope only carries the tenant id.
        TenantContext.runWithTenantIfUnbound(TenantContext.get(), resolveTenantSlug(),
                () -> recomputeInScope(collectionName, fieldName));
    }

    private void recomputeInScope(String collectionName, String fieldName) {
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            log.warn("Cannot recompute formula: collection '{}' not registered", collectionName);
            return;
        }

        FieldDefinition field = findFormulaField(definition, fieldName);
        if (field == null) {
            log.warn("Cannot recompute formula: '{}.{}' is not a FORMULA field", collectionName, fieldName);
            return;
        }

        String expression = expressionOf(field);
        if (expression == null || expression.isBlank()) {
            log.info("Formula '{}.{}' has no expression — nothing to recompute", collectionName, fieldName);
            return;
        }

        String tableName = baseTableName(definition);
        String qualifiedTable = qualifiedTableName(definition, tableName);

        int page = 1;
        int totalProcessed = 0;
        while (true) {
            QueryRequest request = new QueryRequest(
                    new Pagination(page, PAGE_SIZE), List.of(), List.of(), List.of());
            QueryResult result = storageAdapter.query(definition, request);
            List<Map<String, Object>> records = result == null ? List.of() : result.data();
            if (records == null || records.isEmpty()) {
                break;
            }

            List<Object[]> batchArgs = new java.util.ArrayList<>(records.size());
            for (Map<String, Object> record : records) {
                Object id = record.get("id");
                if (id == null) {
                    continue;
                }
                evaluateQuietly(expression, record, collectionName, fieldName);
                batchArgs.add(new Object[]{id.toString()});
            }
            if (!batchArgs.isEmpty()) {
                jdbcTemplate.batchUpdate(
                        "UPDATE " + qualifiedTable + " SET updated_at = updated_at WHERE id = ?",
                        batchArgs);
                totalProcessed += batchArgs.size();
            }

            if (records.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        log.info("Formula recompute complete for {}.{}: {} records processed",
                collectionName, fieldName, totalProcessed);
    }

    private void evaluateQuietly(String expression, Map<String, Object> record,
                                 String collectionName, String fieldName) {
        try {
            formulaEvaluator.evaluate(expression, record);
        } catch (RuntimeException e) {
            // Per-record evaluation failures are recorded on read by
            // DefaultQueryEngine.computeFormulaValue — log at debug here so a
            // single bad row does not flood the recompute log.
            log.debug("Formula evaluation during recompute failed for {}.{} (id={}): {}",
                    collectionName, fieldName, record.get("id"), e.getMessage());
        }
    }

    private static FieldDefinition findFormulaField(CollectionDefinition definition, String fieldName) {
        for (FieldDefinition field : definition.fields()) {
            if (fieldName.equals(field.name()) && field.type() == FieldType.FORMULA) {
                return field;
            }
        }
        return null;
    }

    private static String expressionOf(FieldDefinition field) {
        Object configured = field.getConfigValue("expression");
        return configured instanceof String s ? s : null;
    }

    private static String baseTableName(CollectionDefinition definition) {
        if (definition.storageConfig() != null && definition.storageConfig().tableName() != null) {
            return definition.storageConfig().tableName();
        }
        return definition.name();
    }

    /**
     * Schema-qualifies the table name the same way {@code PhysicalTableStorageAdapter}
     * does: system collections live in {@code public}; tenant-scoped collections
     * live in the tenant's schema.
     */
    private static String qualifiedTableName(CollectionDefinition definition, String tableName) {
        String safeTable = sanitize(tableName);
        if (definition.systemCollection()) {
            return "\"public\".\"" + safeTable + "\"";
        }
        String slug = TenantContext.getSlug();
        if (slug != null && !slug.isBlank()) {
            return "\"" + sanitize(slug) + "\".\"" + safeTable + "\"";
        }
        return "\"public\".\"" + safeTable + "\"";
    }

    private static String sanitize(String identifier) {
        // Defense in depth: tenant slugs and table names already match the
        // platform's identifier grammar; double any embedded quote anyway
        // before splicing into DDL/DML text.
        return identifier.replace("\"", "\"\"");
    }

    /**
     * The slug already in scope, else the bound tenant's slug looked up by id, else {@code null}.
     */
    private String resolveTenantSlug() {
        if (TenantContext.getSlug() != null && !TenantContext.getSlug().isBlank()) {
            return TenantContext.getSlug();
        }
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> row = jdbcTemplate.queryForList(SELECT_TENANT_SLUG, tenantId)
                    .stream().findFirst().orElse(new HashMap<>());
            Object slug = row.get("slug");
            if (slug instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (Exception e) {
            log.debug("Could not resolve slug for tenant {}: {}", tenantId, e.getMessage());
        }
        return null;
    }
}
