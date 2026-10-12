package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Derives a URL-safe {@code slug} for a UI page when the client doesn't supply one.
 *
 * <p>The {@code ui_page.slug} column is {@code NOT NULL} and {@code UNIQUE(tenant_id, slug)}
 * (V116), but the page-builder UI / API / MCP create payloads carry {@code name}/{@code path}
 * but no slug — so a create would violate the not-null constraint and 500. This hook fills the
 * gap server-side (one fix for every client): it slugifies {@code name} (falling back to
 * {@code path}, then {@code title}), and appends {@code -2}, {@code -3}, … until the slug is
 * unique for the tenant.
 *
 * <p>An explicitly-supplied non-blank slug is left untouched.
 */
@Component("uiPageSlugHook")
public class UIPageSlugHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(UIPageSlugHook.class);
    private static final String COLLECTION = "ui-pages";
    private static final int MAX_SLUG_LENGTH = 190; // leave room for a "-NN" disambiguation suffix
    private static final String FALLBACK_SLUG = "page";

    private final JdbcTemplate jdbcTemplate;

    public UIPageSlugHook(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getCollectionName() {
        return COLLECTION;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        if (hasText(record.get("slug"))) {
            return BeforeSaveResult.ok();
        }
        String base = slugify(firstNonBlank(record.get("name"), record.get("path"), record.get("title")));
        String unique = makeUnique(base, tenantId);
        log.debug("Derived ui-page slug '{}' for tenant {}", unique, tenantId);
        return BeforeSaveResult.withFieldUpdates(Map.of("slug", unique));
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        // Only act when an update explicitly blanks the slug; never override an existing one.
        if (!record.containsKey("slug") || hasText(record.get("slug"))) {
            return BeforeSaveResult.ok();
        }
        String base = slugify(firstNonBlank(record.get("name"), record.get("path"), record.get("title")));
        return BeforeSaveResult.withFieldUpdates(Map.of("slug", makeUnique(base, tenantId)));
    }

    /** Lowercase, collapse non-alphanumeric runs to '-', trim '-', cap length. */
    static String slugify(String raw) {
        if (raw == null) {
            return FALLBACK_SLUG;
        }
        String slug = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? FALLBACK_SLUG : slug;
    }

    private String makeUnique(String base, String tenantId) {
        String candidate = base;
        int suffix = 2;
        while (slugExists(candidate, tenantId)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private boolean slugExists(String slug, String tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ui_page WHERE tenant_id = ? AND slug = ?",
                Integer.class, tenantId, slug);
        return count != null && count > 0;
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (hasText(value)) {
                return value.toString();
            }
        }
        return null;
    }

    private static boolean hasText(Object value) {
        return value instanceof String str && !str.isBlank();
    }
}
