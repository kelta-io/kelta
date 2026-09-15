package io.kelta.worker.controller;

import io.kelta.worker.service.PageWidgetCatalog;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the page-authoring vocabulary: the built-in widget catalogue and the JSON Schema for a
 * {@code ui-pages.config} document. Both were previously discoverable only by reading kelta-ui
 * source, which left every non-browser author (API, MCP, CLI, agent) guessing at widget types and
 * prop names.
 *
 * <p>Both responses are generated artifacts served verbatim from the classpath — see
 * {@link PageWidgetCatalog}. They describe the platform, not the tenant, so they carry no tenant
 * data and need no authorization beyond the route's {@code API_ACCESS}.
 *
 * <p>{@code /api/pages/**} is already a gateway static route (registered for
 * {@link PageRenderController}), so no route registration is needed.
 */
@RestController
@RequestMapping("/api/pages")
public class PageWidgetsController {

    private final PageWidgetCatalog catalog;

    public PageWidgetsController(PageWidgetCatalog catalog) {
        this.catalog = catalog;
    }

    /** {@code {"widgets":[{type,label,category,acceptsChildren,defaultProps,propSchema,source}]}}. */
    @GetMapping(value = "/widgets", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> widgets() {
        return ResponseEntity.ok(catalog.catalogueJson());
    }

    /** The JSON Schema a {@code ui-pages.config} document is authored against. */
    @GetMapping(value = "/config-schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> configSchema() {
        return ResponseEntity.ok(catalog.configSchemaJson());
    }
}
