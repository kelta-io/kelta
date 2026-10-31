package io.kelta.ai.controller;

import io.kelta.ai.repository.AiConfigRepository;
import io.kelta.jsonapi.JsonApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The tenant's AI settings: model, max tokens, temperature, monthly token quota and the AI on/off
 * switch.
 *
 * <p>Reads ({@code GET}) need only {@code API_ACCESS}. Writes ({@code PUT}) need
 * {@code MANAGE_TENANTS}, the platform-administrator grant that also gates governor-limit writes;
 * the gateway enforces it on the {@code static-ai} route ({@code RouteAuthorizationFilter}), since
 * kelta-ai has no permission data of its own. Every key is a platform control — the monthly token
 * quota and the on/off switch are what {@code TokenLimitFilter} enforces, and the model and max
 * tokens decide what a tenant's requests cost — so none of them is a tenant administrator's to
 * raise. The admin UI's AI settings page carries the same gate.
 *
 * <p>A write accepts only the keys in {@link #CONFIG_KEYS}, each validated before anything is
 * stored; an unknown key or an invalid value is a {@code 400 VALIDATION_FAILED} (one error per
 * key, {@code source.pointer} naming it) and nothing is written.
 */
@RestController
@RequestMapping("/api/ai/config")
public class AiConfigController {

    private static final Logger log = LoggerFactory.getLogger(AiConfigController.class);

    /** Request key → {@code ai_config.config_key}; the only keys a write accepts, in this order. */
    static final Map<String, String> CONFIG_KEYS;

    static {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("model", "anthropic.model");
        keys.put("maxTokens", "anthropic.maxTokens");
        keys.put("temperature", "anthropic.temperature");
        keys.put("aiTokensPerMonth", "aiTokensPerMonth");
        keys.put("aiEnabled", "aiEnabled");
        CONFIG_KEYS = Collections.unmodifiableMap(keys);
    }

    static final int MODEL_MAX_LENGTH = 200;

    /** A provider model id: no whitespace or control characters. */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/-]*");

    private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private final AiConfigRepository configRepository;

    public AiConfigController(AiConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig(
            @RequestHeader("X-Tenant-ID") String tenantId) {

        Map<String, String> config = configRepository.getAllConfig(tenantId);

        // Also include global defaults (tenant 0) for any missing keys
        Map<String, String> globalConfig = configRepository.getAllConfig("0");
        globalConfig.forEach(config::putIfAbsent);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", config.getOrDefault("anthropic.model", "claude-sonnet-4-20250514"));
        data.put("maxTokens", config.getOrDefault("anthropic.maxTokens", "4096"));
        data.put("temperature", config.getOrDefault("anthropic.temperature", "0.7"));
        data.put("aiTokensPerMonth", config.getOrDefault("aiTokensPerMonth", "1000000"));
        data.put("aiEnabled", config.getOrDefault("aiEnabled", "true"));

        return ResponseEntity.ok(Map.of("data", data));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestBody Map<String, Object> body) {

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> values = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            String configKey = CONFIG_KEYS.get(key);
            if (configKey == null) {
                errors.add(error(key, "Unknown AI config key; accepted keys: "
                        + String.join(", ", CONFIG_KEYS.keySet()), requestId));
                continue;
            }
            Object value = entry.getValue();
            Optional<String> normalized = value == null ? Optional.empty() : normalize(key, value);
            if (normalized.isEmpty()) {
                errors.add(error(key, describe(key), requestId));
                continue;
            }
            values.put(configKey, normalized.get());
        }

        if (!errors.isEmpty()) {
            log.warn("AI config update for tenant {} refused [requestId={}]: {} invalid key(s)",
                    tenantId, requestId, errors.size());
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }

        log.info("Updating AI config for tenant {}: {}", tenantId, values.keySet());
        values.forEach((configKey, value) -> configRepository.setConfig(tenantId, configKey, value));

        return getConfig(tenantId);
    }

    /** The stored form of a valid value, or empty when {@code value} is not valid for {@code key}. */
    static Optional<String> normalize(String key, Object value) {
        return switch (key) {
            case "model" -> value instanceof String s
                    && !s.isBlank()
                    && s.length() <= MODEL_MAX_LENGTH
                    && MODEL_ID.matcher(s).matches()
                    ? Optional.of(s)
                    : Optional.empty();
            case "maxTokens" -> wholeNumber(value)
                    .filter(n -> n.signum() > 0 && n.compareTo(MAX_INT) <= 0)
                    .map(BigInteger::toString);
            case "aiTokensPerMonth" -> wholeNumber(value)
                    .filter(n -> n.signum() > 0 && n.compareTo(MAX_LONG) <= 0)
                    .map(BigInteger::toString);
            case "temperature" -> decimal(value)
                    .filter(t -> t.signum() >= 0 && t.compareTo(BigDecimal.ONE) <= 0)
                    .map(t -> t.stripTrailingZeros().toPlainString());
            case "aiEnabled" -> bool(value).map(String::valueOf);
            default -> Optional.empty();
        };
    }

    private static String describe(String key) {
        return switch (key) {
            case "model" -> "model must be a non-blank model id of at most " + MODEL_MAX_LENGTH
                    + " characters (letters, digits and . _ : @ / -)";
            case "maxTokens" -> "maxTokens must be a whole number from 1 to " + Integer.MAX_VALUE;
            case "aiTokensPerMonth" -> "aiTokensPerMonth must be a whole number from 1 to " + Long.MAX_VALUE;
            case "temperature" -> "temperature must be a number from 0 to 1";
            case "aiEnabled" -> "aiEnabled must be true or false";
            default -> key + " is not valid";
        };
    }

    /** A JSON number or numeric string; booleans and other types are not numbers. */
    private static Optional<BigDecimal> decimal(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(value.toString().trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigInteger> wholeNumber(Object value) {
        return decimal(value).flatMap(d -> {
            try {
                return Optional.of(d.toBigIntegerExact());
            } catch (ArithmeticException e) {
                return Optional.empty();
            }
        });
    }

    private static Optional<Boolean> bool(Object value) {
        if (value instanceof Boolean b) {
            return Optional.of(b);
        }
        if (value instanceof String s) {
            String t = s.trim();
            if ("true".equalsIgnoreCase(t)) {
                return Optional.of(Boolean.TRUE);
            }
            if ("false".equalsIgnoreCase(t)) {
                return Optional.of(Boolean.FALSE);
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> error(String key, String detail, String requestId) {
        JsonApiError error = new JsonApiError("400", "VALIDATION_FAILED", "Validation Error", detail);
        error.setSource(Map.of("pointer", "/" + key.replace("~", "~0").replace("/", "~1")));
        error.setMeta(Map.of("requestId", requestId));
        return error.toMap();
    }
}
