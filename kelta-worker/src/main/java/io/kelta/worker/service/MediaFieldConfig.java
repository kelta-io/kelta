package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A field's media configuration: the field holds the storage key of an object uploaded
 * directly to the platform media bucket ({@link MediaStorageService}).
 *
 * <p>Lives in the field's {@code fieldTypeConfig} under key {@code "media"}, the same way data
 * masking lives under {@code "masking"}:
 *
 * <pre>{@code
 * { "media": { "enabled": true,
 *              "contentTypes": ["image/"],   // optional; "x/" is a prefix, else exact. Default ["image/"]
 *              "maxFileSize": 15728640 } }   // optional, bytes; capped by the platform limit
 * }</pre>
 *
 * <p>Fail-closed parsing: anything but {@code "enabled": true} is "not a media field", so a typo
 * never opens an upload surface. Only {@link #ELIGIBLE_TYPES} qualify — the stored value is a
 * plain storage key. Changing the config is an ordinary field update, so it reaches every pod
 * through the existing field-change broadcast ({@code FieldConfigEventPublisher}).
 *
 * @param fieldName    the media field
 * @param contentTypes accepted content types: entries ending in {@code /} match by prefix
 * @param maxFileSize  per-field limit in bytes, or {@code null} for the platform limit
 */
public record MediaFieldConfig(String fieldName, List<String> contentTypes, Long maxFileSize) {

    /** Config key inside {@code fieldTypeConfig}. */
    public static final String CONFIG_KEY = "media";

    /** Field types that can hold a storage key. */
    public static final Set<FieldType> ELIGIBLE_TYPES = EnumSet.of(FieldType.STRING, FieldType.TEXT);

    /** Accepted when a media config names no content types. */
    public static final List<String> DEFAULT_CONTENT_TYPES = List.of("image/");

    /**
     * Never accepted, whatever a field lists: executables, and types a browser would render as
     * active content if a presigned URL were opened directly.
     */
    private static final Set<String> BLOCKED_CONTENT_TYPES = Set.of(
            "application/x-executable",
            "application/x-sharedlib",
            "application/x-msdos-program",
            "text/html",
            "application/xhtml+xml",
            "application/javascript",
            "text/javascript");

    /**
     * SVG can carry script. It is accepted only when a field lists it exactly — never through a
     * prefix such as {@code image/}.
     */
    private static final String SVG = "image/svg+xml";

    /** {@code type/subtype}, no parameters (RFC 6838 restricted-name characters). */
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$");

    public MediaFieldConfig {
        contentTypes = contentTypes == null || contentTypes.isEmpty()
                ? DEFAULT_CONTENT_TYPES : List.copyOf(contentTypes);
    }

    /** The field's media config, or empty when it is not an enabled media field. */
    public static Optional<MediaFieldConfig> of(FieldDefinition field) {
        if (field == null || field.type() == null || !ELIGIBLE_TYPES.contains(field.type())) {
            return Optional.empty();
        }
        if (!(field.getConfigValue(CONFIG_KEY) instanceof Map<?, ?> cfg)
                || !Boolean.TRUE.equals(cfg.get("enabled"))) {
            return Optional.empty();
        }

        List<String> types = new ArrayList<>();
        if (cfg.get("contentTypes") instanceof List<?> listed) {
            for (Object entry : listed) {
                if (entry instanceof String s && !s.isBlank()) {
                    types.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        Long max = cfg.get("maxFileSize") instanceof Number n && n.longValue() > 0 ? n.longValue() : null;
        return Optional.of(new MediaFieldConfig(field.name(), types, max));
    }

    /** Every enabled media field on a collection, in field order. */
    public static List<MediaFieldConfig> mediaFields(CollectionDefinition definition) {
        if (definition == null || definition.fields() == null) {
            return List.of();
        }
        List<MediaFieldConfig> result = new ArrayList<>();
        for (FieldDefinition field : definition.fields()) {
            of(field).ifPresent(result::add);
        }
        return result;
    }

    /** Whether an upload declaring {@code contentType} is accepted by this field. */
    public boolean accepts(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.trim().toLowerCase(Locale.ROOT);
        if (!CONTENT_TYPE.matcher(type).matches() || BLOCKED_CONTENT_TYPES.contains(type)) {
            return false;
        }
        for (String allowed : contentTypes) {
            if (allowed.equals(type)) {
                return true;
            }
            if (allowed.endsWith("/") && type.startsWith(allowed) && !SVG.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** The effective size limit: the field's own, never above the platform's. */
    public long effectiveMaxFileSize(long platformMax) {
        return maxFileSize == null ? platformMax : Math.min(maxFileSize, platformMax);
    }
}
