package io.kelta.worker.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The built-in page-widget catalogue and the {@code ui-pages.config} JSON Schema, as served by
 * {@link io.kelta.worker.controller.PageWidgetsController} and consulted by
 * {@link UiPageConfigValidator}.
 *
 * <p>Both resources are generated from kelta-ui — the widget registry is the single source of the
 * widget vocabulary, and duplicating it by hand here would drift the moment a widget is added. The
 * generator is {@code kelta-ui/app/scripts/generate-page-widgets.ts} and a vitest freshness pin
 * fails when the registry changes without a regeneration.
 *
 * <p>The files are read once at startup and held as raw JSON so the endpoints can stream them
 * without a parse/serialize round trip. A missing resource is fatal: it means the build shipped
 * without the generated artifacts, and every page save would then reject every widget type.
 *
 * @since 1.0.0
 */
@Service
public class PageWidgetCatalog {

    private static final String CATALOGUE_RESOURCE = "page-widgets.json";
    private static final String CONFIG_SCHEMA_RESOURCE = "schema/ui-page-config.schema.json";

    private final String catalogueJson;
    private final String configSchemaJson;
    private final Set<String> builtinTypes;

    public PageWidgetCatalog(ObjectMapper objectMapper) {
        this.catalogueJson = readResource(CATALOGUE_RESOURCE);
        this.configSchemaJson = readResource(CONFIG_SCHEMA_RESOURCE);
        this.builtinTypes = readTypes(objectMapper, this.catalogueJson);
    }

    /** The catalogue document — {@code {"widgets":[{type,label,category,propSchema,…}]}}. */
    public String catalogueJson() {
        return catalogueJson;
    }

    /** The JSON Schema for a {@code ui-pages.config} document. */
    public String configSchemaJson() {
        return configSchemaJson;
    }

    /** Every widget {@code type} the builder registers as a built-in. */
    public Set<String> builtinTypes() {
        return builtinTypes;
    }

    public boolean isBuiltinType(String type) {
        return builtinTypes.contains(type);
    }

    private static String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Generated page resource '" + path + "' is missing from the worker classpath; "
                            + "run `npm run gen:page-widgets` in kelta-ui/app", e);
        }
    }

    private static Set<String> readTypes(ObjectMapper objectMapper, String catalogueJson) {
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode widget : objectMapper.readTree(catalogueJson).path("widgets")) {
            String type = widget.path("type").asText(null);
            if (type != null && !type.isBlank()) {
                types.add(type);
            }
        }
        return Set.copyOf(types);
    }
}
