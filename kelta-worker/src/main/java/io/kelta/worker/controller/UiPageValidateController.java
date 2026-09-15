package io.kelta.worker.controller;

import io.kelta.worker.service.UiPageConfigValidator;
import io.kelta.worker.service.UiPageConfigValidator.Problem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dry-run validation of a {@code ui-pages.config} document, using the same
 * {@link UiPageConfigValidator} the write-path {@link io.kelta.worker.service.UiPageConfigHook}
 * runs — so a builder can check a page before it saves and get the same verdict.
 *
 * <p>The body is the config itself (optionally wrapped as <code>{"config": {…}}</code>, which is
 * what a caller holding a whole {@code ui-pages} record already has).
 *
 * <p>{@code valid} answers "would this save?" — it is false only when a problem of severity
 * {@code error} was found. Warnings are reported alongside but do not block a save.
 */
@RestController
@RequestMapping("/api/ui-pages")
public class UiPageValidateController {

    private final UiPageConfigValidator validator;

    public UiPageValidateController(UiPageConfigValidator validator) {
        this.validator = validator;
    }

    /** @return {@code {valid, errors:[{path, message, severity}]}} */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(
            @RequestBody(required = false) Map<String, Object> body) {

        List<Problem> problems = validator.validate(unwrapConfig(body));

        List<Map<String, Object>> errors = new ArrayList<>(problems.size());
        for (Problem problem : problems) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", problem.path());
            entry.put("message", problem.message());
            entry.put("severity", problem.severity().name().toLowerCase(java.util.Locale.ROOT));
            errors.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", validator.isSaveable(problems));
        result.put("errors", errors);
        return ResponseEntity.ok(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapConfig(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        if (body.get("config") instanceof Map<?, ?> config) {
            return (Map<String, Object>) config;
        }
        return body;
    }
}
