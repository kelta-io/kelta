package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldDefinitionBuilder;
import io.kelta.runtime.model.FieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MediaFieldConfig")
class MediaFieldConfigTest {

    private static FieldDefinition field(String name, FieldType type, Map<String, Object> config) {
        return new FieldDefinitionBuilder().name(name).type(type).nullable(true).fieldTypeConfig(config).build();
    }

    private static FieldDefinition media(Map<String, Object> mediaConfig) {
        return field("photo", FieldType.STRING, Map.of("media", mediaConfig));
    }

    @Test
    @DisplayName("enabled: true on a STRING or TEXT field makes it a media field, defaulting to images")
    void enabledField() {
        assertThat(MediaFieldConfig.of(media(Map.of("enabled", true))))
                .hasValueSatisfying(cfg -> {
                    assertThat(cfg.fieldName()).isEqualTo("photo");
                    assertThat(cfg.contentTypes()).containsExactly("image/");
                    assertThat(cfg.maxFileSize()).isNull();
                });
        assertThat(MediaFieldConfig.of(field("doc", FieldType.TEXT, Map.of("media", Map.of("enabled", true)))))
                .isPresent();
    }

    @Test
    @DisplayName("fails closed: absent, disabled, non-boolean and non-map configs are not media fields")
    void failsClosed() {
        assertThat(MediaFieldConfig.of(field("photo", FieldType.STRING, null))).isEmpty();
        assertThat(MediaFieldConfig.of(media(Map.of()))).isEmpty();
        assertThat(MediaFieldConfig.of(media(Map.of("enabled", false)))).isEmpty();
        assertThat(MediaFieldConfig.of(media(Map.of("enabled", "true")))).isEmpty();
        assertThat(MediaFieldConfig.of(field("photo", FieldType.STRING, Map.of("media", true)))).isEmpty();
        assertThat(MediaFieldConfig.of(null)).isEmpty();
    }

    @Test
    @DisplayName("only types that hold a plain key qualify, whatever the config says")
    void ineligibleTypes() {
        for (FieldType type : List.of(FieldType.INTEGER, FieldType.JSON, FieldType.URL, FieldType.ENCRYPTED)) {
            assertThat(MediaFieldConfig.of(field("f", type, Map.of("media", Map.of("enabled", true)))))
                    .as(type.name()).isEmpty();
        }
    }

    @Test
    @DisplayName("prefix entries match by prefix, exact entries exactly, case-insensitively")
    void contentTypeMatching() {
        MediaFieldConfig cfg = MediaFieldConfig.of(media(Map.of("enabled", true,
                "contentTypes", List.of("image/", "Application/PDF")))).orElseThrow();

        assertThat(cfg.accepts("image/jpeg")).isTrue();
        assertThat(cfg.accepts("IMAGE/PNG")).isTrue();
        assertThat(cfg.accepts("application/pdf")).isTrue();
        assertThat(cfg.accepts("application/pdfx")).isFalse();
        assertThat(cfg.accepts("video/mp4")).isFalse();
        assertThat(cfg.accepts(null)).isFalse();
    }

    @Test
    @DisplayName("SVG only when listed exactly; active content and malformed types never")
    void blockedAndMalformed() {
        MediaFieldConfig images = MediaFieldConfig.of(media(Map.of("enabled", true))).orElseThrow();
        assertThat(images.accepts("image/svg+xml")).isFalse();
        assertThat(images.accepts("image/jpeg; charset=binary")).isFalse();
        assertThat(images.accepts("image/")).isFalse();
        assertThat(images.accepts("image/jpeg\r\nX-Injected: 1")).isFalse();

        MediaFieldConfig svg = MediaFieldConfig.of(media(Map.of("enabled", true,
                "contentTypes", List.of("image/svg+xml")))).orElseThrow();
        assertThat(svg.accepts("image/svg+xml")).isTrue();

        MediaFieldConfig html = MediaFieldConfig.of(media(Map.of("enabled", true,
                "contentTypes", List.of("text/html", "application/x-executable")))).orElseThrow();
        assertThat(html.accepts("text/html")).isFalse();
        assertThat(html.accepts("application/x-executable")).isFalse();
    }

    @Test
    @DisplayName("a field limit lowers the platform limit and can never raise it")
    void sizeLimit() {
        MediaFieldConfig lower = MediaFieldConfig.of(media(Map.of("enabled", true, "maxFileSize", 1000)))
                .orElseThrow();
        assertThat(lower.effectiveMaxFileSize(5000)).isEqualTo(1000);

        MediaFieldConfig higher = MediaFieldConfig.of(media(Map.of("enabled", true, "maxFileSize", 9000)))
                .orElseThrow();
        assertThat(higher.effectiveMaxFileSize(5000)).isEqualTo(5000);

        MediaFieldConfig invalid = MediaFieldConfig.of(media(Map.of("enabled", true, "maxFileSize", -1)))
                .orElseThrow();
        assertThat(invalid.effectiveMaxFileSize(5000)).isEqualTo(5000);
    }

    @Test
    @DisplayName("mediaFields lists every enabled media field of a collection in order")
    void mediaFieldsOfCollection() {
        CollectionDefinition definition = CollectionDefinition.builder()
                .name("listings")
                .addField(FieldDefinition.string("title"))
                .addField(field("cover", FieldType.STRING, Map.of("media", Map.of("enabled", true))))
                .addField(field("plan", FieldType.TEXT, Map.of("media", Map.of("enabled", true))))
                .addField(field("draft", FieldType.STRING, Map.of("media", Map.of("enabled", false))))
                .build();

        assertThat(MediaFieldConfig.mediaFields(definition))
                .extracting(MediaFieldConfig::fieldName).containsExactly("cover", "plan");
        assertThat(MediaFieldConfig.mediaFields(null)).isEmpty();
    }
}
