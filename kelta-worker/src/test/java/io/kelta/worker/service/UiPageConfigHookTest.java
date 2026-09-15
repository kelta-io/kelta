package io.kelta.worker.service;

import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the {@code ui-pages} before-save hook blocks, and what it deliberately lets through. */
@DisplayName("ui-pages before-save hook")
class UiPageConfigHookTest {

    private static final String TENANT = "tenant-1";

    private UiPageConfigHook hook;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        hook = new UiPageConfigHook(
                new UiPageConfigValidator(new PageWidgetCatalog(objectMapper)), objectMapper);
    }

    private static Map<String, Object> pageWith(Object config) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", "Orders");
        record.put("path", "/orders");
        record.put("config", config);
        return record;
    }

    private static Map<String, Object> configWithType(String type) {
        return Map.of("schemaVersion", 2,
                "components", List.of(Map.of("id", "c1", "type", type, "props", Map.of())));
    }

    @Test
    @DisplayName("rejects a create whose config uses an unknown widget type")
    void rejectsUnknownWidgetTypeOnCreate() {
        BeforeSaveResult result = hook.beforeCreate(pageWith(configWithType("nope")), TENANT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("config/components/0/type");
            assertThat(error.message()).contains("nope");
        });
    }

    @Test
    @DisplayName("accepts a create built from catalogue widgets")
    void acceptsKnownWidgetTypeOnCreate() {
        assertThat(hook.beforeCreate(pageWith(configWithType("heading")), TENANT).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a page whose only problem is a soft warning still saves")
    void warningOnlyPageStillSaves() {
        Map<String, Object> config = Map.of("components", List.of(Map.of(
                "id", "t1", "type", "text",
                "props", Map.of("content", "{{data.missing.length}}"))));

        assertThat(hook.beforeCreate(pageWith(config), TENANT).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("validates the effective record on update, not just the delta")
    void updateValidatesMergedRecord() {
        Map<String, Object> previous = pageWith(configWithType("nope"));
        Map<String, Object> delta = Map.of("published", true);

        assertThat(hook.beforeUpdate("page-1", delta, previous, TENANT).isSuccess())
                .as("a publish flip cannot slip an already-broken config past the hook")
                .isFalse();
    }

    @Test
    @DisplayName("an update that repairs the config is accepted")
    void updateWithRepairedConfigIsAccepted() {
        Map<String, Object> previous = pageWith(configWithType("nope"));
        Map<String, Object> delta = Map.of("config", configWithType("heading"));

        assertThat(hook.beforeUpdate("page-1", delta, previous, TENANT).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("config sent as a raw JSON string is validated the same way")
    void rawJsonConfigIsValidated() {
        String json = "{\"components\":[{\"id\":\"c1\",\"type\":\"nope\",\"props\":{}}]}";

        assertThat(hook.beforeCreate(pageWith(json), TENANT).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("a write without a config, or with unparseable JSON, passes through untouched")
    void absentOrUnparseableConfigPassesThrough() {
        Map<String, Object> noConfig = new LinkedHashMap<>();
        noConfig.put("name", "Orders");

        assertThat(hook.beforeCreate(noConfig, TENANT).isSuccess()).isTrue();
        assertThat(hook.beforeCreate(pageWith("{not json"), TENANT).isSuccess()).isTrue();
        assertThat(hook.beforeUpdate("page-1", Map.of("name", "Renamed"), null, TENANT).isSuccess())
                .isTrue();
    }

    @Test
    @DisplayName("runs ahead of the slug hook and the change publisher")
    void ordersAheadOfTheSideEffectHooks() {
        assertThat(hook.getCollectionName()).isEqualTo("ui-pages");
        assertThat(hook.getOrder()).isLessThan(200);
    }
}
