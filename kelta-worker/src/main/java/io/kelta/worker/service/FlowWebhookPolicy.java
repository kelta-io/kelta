package io.kelta.worker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-flow inbound webhook settings, read from {@code triggerConfig.webhook}:
 * <pre>
 * {"methods": ["POST", "GET"], "bodies": ["json", "form"], "secret": "...",
 *  "response": {"status": 200, "body": "...", "contentType": "text/plain"} | {"redirect": "https://..."}}
 * </pre>
 * An absent block is the original contract: POST, JSON body, no secret, JSON:API response.
 * GET and form bodies are only accepted when a secret is set, because a browser or mail client
 * can be made to send them without the caller knowing the flow is being started.
 *
 * <p>A request authenticates with the secret in {@value #SECRET_HEADER}, or with a {@value #SIGNATURE_PARAM}
 * query parameter holding the hex HMAC-SHA256 of {@link #canonicalQuery the remaining query parameters}.
 * Neither the secret nor the signature is ever logged.
 */
public record FlowWebhookPolicy(Set<String> methods, Set<BodyType> bodies, String secret, Response response) {

    private static final Logger log = LoggerFactory.getLogger(FlowWebhookPolicy.class);

    public static final String SECRET_HEADER = "X-Kelta-Webhook-Secret";
    public static final String SIGNATURE_PARAM = "sig";
    public static final int MAX_RESPONSE_BODY_BYTES = 4096;
    private static final int MAX_REDIRECT_LENGTH = 2048;
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST");
    private static final Set<String> RESPONSE_CONTENT_TYPES = Set.of("text/plain", "text/html", "application/json");

    public static final FlowWebhookPolicy DEFAULT =
            new FlowWebhookPolicy(Set.of("POST"), Set.of(BodyType.JSON), null, null);

    public enum BodyType { JSON, FORM }

    /** A configured reply that replaces the default JSON:API {@code flow-executions} response. */
    public sealed interface Response permits Content, Redirect {}

    public record Content(int status, String body, String contentType) implements Response {}

    public record Redirect(URI location) implements Response {}

    /** {@code triggerConfig.webhook} cannot be honoured; the webhook refuses every request. */
    public static final class InvalidConfigException extends RuntimeException {
        public InvalidConfigException(String message) {
            super(message);
        }
    }

    /**
     * Reads the policy from a flow's {@code trigger_config} JSON. A missing or unreadable config,
     * or one without a {@code webhook} block, yields {@link #DEFAULT}.
     *
     * @throws InvalidConfigException when the {@code webhook} block is present but invalid
     */
    @SuppressWarnings("unchecked")
    public static FlowWebhookPolicy parse(String triggerConfigJson, ObjectMapper objectMapper) {
        if (triggerConfigJson == null || triggerConfigJson.isBlank()) {
            return DEFAULT;
        }
        Object parsed;
        try {
            parsed = objectMapper.readValue(triggerConfigJson, Object.class);
        } catch (JacksonException e) {
            return DEFAULT;
        }
        return parsed instanceof Map<?, ?> config ? fromTriggerConfig((Map<String, Object>) config) : DEFAULT;
    }

    static FlowWebhookPolicy fromTriggerConfig(Map<String, Object> triggerConfig) {
        Object webhook = triggerConfig.get("webhook");
        if (webhook == null) {
            return DEFAULT;
        }
        if (!(webhook instanceof Map<?, ?> block)) {
            throw new InvalidConfigException("triggerConfig.webhook must be an object");
        }
        Set<String> methods = readSet(block.get("methods"), "methods", DEFAULT.methods(), value -> {
            String method = value.toUpperCase(Locale.ROOT);
            if (!ALLOWED_METHODS.contains(method)) {
                throw new InvalidConfigException("triggerConfig.webhook.methods supports GET and POST only");
            }
            return method;
        });
        Set<BodyType> bodies = readSet(block.get("bodies"), "bodies", DEFAULT.bodies(), value -> switch (value.toLowerCase(Locale.ROOT)) {
            case "json" -> BodyType.JSON;
            case "form" -> BodyType.FORM;
            default -> throw new InvalidConfigException("triggerConfig.webhook.bodies supports json and form only");
        });

        Object rawSecret = block.get("secret");
        if (rawSecret != null && (!(rawSecret instanceof String s) || s.isBlank())) {
            throw new InvalidConfigException("triggerConfig.webhook.secret must be a non-empty string");
        }
        String secret = (String) rawSecret;
        if (secret == null && (methods.contains("GET") || bodies.contains(BodyType.FORM))) {
            throw new InvalidConfigException(
                    "GET and form bodies require triggerConfig.webhook.secret to be set");
        }
        return new FlowWebhookPolicy(methods, bodies, secret, readResponse(block.get("response")));
    }

    public boolean allowsMethod(String method) {
        return methods.contains(method.toUpperCase(Locale.ROOT));
    }

    public boolean allowsBody(BodyType bodyType) {
        return bodies.contains(bodyType);
    }

    /**
     * Whether the request carries this flow's secret: the {@value #SECRET_HEADER} header, or a
     * valid {@value #SIGNATURE_PARAM} over the other query parameters. Always true without a secret.
     *
     * @param headerSecret the {@value #SECRET_HEADER} header value, or null
     * @param query        every query parameter, {@value #SIGNATURE_PARAM} included
     */
    public boolean authenticates(String headerSecret, Map<String, List<String>> query) {
        if (secret == null) {
            return true;
        }
        if (headerSecret != null && constantTimeEquals(secret, headerSecret)) {
            return true;
        }
        List<String> signatures = query.get(SIGNATURE_PARAM);
        if (signatures == null || signatures.size() != 1) {
            return false;
        }
        String expected = sign(secret, canonicalQuery(query));
        return constantTimeEquals(expected, signatures.getFirst().toLowerCase(Locale.ROOT));
    }

    /**
     * The string a {@value #SIGNATURE_PARAM} signs: every other decoded parameter as {@code name=value},
     * ordered by name then value, joined with {@code &}.
     */
    public static String canonicalQuery(Map<String, List<String>> query) {
        return query.entrySet().stream()
                .filter(e -> !SIGNATURE_PARAM.equals(e.getKey()))
                .flatMap(e -> e.getValue().stream().map(v -> Map.entry(e.getKey(), v)))
                .sorted(Map.Entry.<String, String>comparingByKey().thenComparing(Map.Entry.comparingByValue()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    /** Lowercase hex HMAC-SHA256 of {@code message} keyed by {@code secret}. */
    public static String sign(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Decodes a raw {@code application/x-www-form-urlencoded} string (a query string or a form
     * body) into ordered name → values.
     *
     * @throws IllegalArgumentException on malformed percent-encoding
     */
    public static Map<String, List<String>> decodeParameters(String raw) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return params;
    }

    /** Flow-state shape of decoded parameters: a single value as a string, repeated ones as a list. */
    public static Map<String, Object> toStateValues(Map<String, List<String>> params) {
        Map<String, Object> values = new LinkedHashMap<>();
        params.forEach((name, list) -> values.put(name, list.size() == 1 ? list.getFirst() : List.copyOf(list)));
        return values;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static <T> Set<T> readSet(Object raw, String key, Set<T> fallback,
                                      java.util.function.Function<String, T> mapper) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidConfigException("triggerConfig.webhook." + key + " must be a non-empty list");
        }
        Set<T> values = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new InvalidConfigException("triggerConfig.webhook." + key + " must list strings");
            }
            values.add(mapper.apply(s));
        }
        return Set.copyOf(values);
    }

    /** A response config that cannot be honoured falls back to the default JSON:API response. */
    private static Response readResponse(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> config)) {
            return invalidResponse("must be an object");
        }
        if (config.containsKey("redirect")) {
            if (!(config.get("redirect") instanceof String target) || target.length() > MAX_REDIRECT_LENGTH) {
                return invalidResponse("redirect must be an absolute http(s) URL");
            }
            try {
                URI location = new URI(target);
                String scheme = location.getScheme() == null ? "" : location.getScheme().toLowerCase(Locale.ROOT);
                if (!(scheme.equals("http") || scheme.equals("https")) || location.getHost() == null) {
                    return invalidResponse("redirect must be an absolute http(s) URL");
                }
                return new Redirect(location);
            } catch (URISyntaxException e) {
                return invalidResponse("redirect must be an absolute http(s) URL");
            }
        }

        Object rawStatus = config.containsKey("status") ? config.get("status") : 200;
        if (!(rawStatus instanceof Integer status) || status < 200 || status > 299) {
            return invalidResponse("status must be a 2xx integer");
        }
        Object rawBody = config.get("body");
        if (rawBody != null && !(rawBody instanceof String)) {
            return invalidResponse("body must be a string");
        }
        String body = (String) rawBody;
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BODY_BYTES) {
            return invalidResponse("body exceeds " + MAX_RESPONSE_BODY_BYTES + " bytes");
        }
        if (body != null && (status == 204 || status == 205)) {
            return invalidResponse("status " + status + " cannot carry a body");
        }
        Object rawContentType = config.containsKey("contentType") ? config.get("contentType") : "text/plain";
        if (!(rawContentType instanceof String contentType)
                || !RESPONSE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            return invalidResponse("contentType must be one of " + RESPONSE_CONTENT_TYPES.stream()
                    .sorted(Comparator.naturalOrder()).toList());
        }
        return new Content(status, body, contentType.toLowerCase(Locale.ROOT));
    }

    private static Response invalidResponse(String reason) {
        log.warn("Ignoring invalid triggerConfig.webhook.response ({}); using the default response", reason);
        return null;
    }
}
