package io.kelta.worker.service;

import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.worker.service.UiPageConfigValidator.Problem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rejects a {@code ui-pages} write whose {@code config} uses a widget type the builder does not
 * know, or breaks a data-source cap — see {@link UiPageConfigValidator} for the checks. Before
 * this hook nothing validated a page on write, so an unknown type or an over-cap data source was
 * only discovered by opening the page and finding it blank.
 *
 * <p>Only {@link UiPageConfigValidator.Severity#ERROR} problems block. A warning (today: a binding
 * to a data source that is not declared yet) is reported by
 * {@code POST /api/ui-pages/validate} but still saves — a page is authored incrementally, and a
 * builder that cannot save a half-written binding is worse than one that renders it as null.
 *
 * @since 1.0.0
 */
public class UiPageConfigHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(UiPageConfigHook.class);

    private final UiPageConfigValidator validator;
    private final ObjectMapper objectMapper;

    public UiPageConfigHook(UiPageConfigValidator validator, ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getCollectionName() {
        return "ui-pages";
    }

    @Override
    public int getOrder() {
        // Ahead of UIPageSlugHook (slug derivation) and UIPageConfigEventPublisher (200) so a
        // config that is about to be rejected neither consumes a slug nor broadcasts a change.
        return 50;
    }

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return validate(record);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        // A partial update may omit `config` entirely (a rename, a publish flip). Validate the
        // effective record the update would produce, not just the delta.
        Map<String, Object> merged = new LinkedHashMap<>(previous == null ? Map.of() : previous);
        merged.putAll(record);
        return validate(merged);
    }

    private BeforeSaveResult validate(Map<String, Object> record) {
        Map<String, Object> config = readConfig(record.get("config"));
        if (config == null) {
            return BeforeSaveResult.ok();
        }
        List<Problem> problems = validator.validate(config);
        List<BeforeSaveResult.ValidationError> errors = problems.stream()
                .filter(Problem::isError)
                .map(p -> new BeforeSaveResult.ValidationError("config" + p.path(), p.message()))
                .toList();
        return errors.isEmpty() ? BeforeSaveResult.ok() : BeforeSaveResult.errors(errors);
    }

    /** `config` reaches a hook as a parsed map, or as raw JSON when written through a text path. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readConfig(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, Map.class);
            } catch (JacksonException e) {
                // Malformed JSON is the JSON column's own problem, not this hook's — let the
                // storage layer produce its error rather than masking it with a widget complaint.
                log.debug("ui-pages config is not parseable JSON; skipping config validation: {}",
                        e.getMessage());
            }
        }
        return null;
    }
}
