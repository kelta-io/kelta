package io.kelta.runtime.module.integration.handlers;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The headers and query parameters a credential adds to an outbound request, and the redaction
 * of everything they carry. A credential type injects only through {@link #header} and
 * {@link #queryParam}, so whatever it adds is known here and masked as {@value #MASK} in every
 * URL, message and response body the handler returns — those land in flow state and are
 * persisted in {@code flow_step_log} snapshots. What is sent to the remote API is unchanged.
 */
final class CredentialInjection {

    static final String MASK = "***";

    /** A shorter secret is not scrubbed from free text: it would mangle unrelated content. */
    private static final int MIN_SCRUB_LENGTH = 4;

    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, Object> queryParams = new LinkedHashMap<>();
    private final Set<String> secrets = new HashSet<>();

    /**
     * Adds a header; {@code value} and every {@code secretParts} element (e.g. the bare token
     * behind {@code Bearer <token>}) are redacted from returned text.
     */
    void header(String name, String value, Object... secretParts) {
        headers.put(name, value);
        addSecret(value);
        for (Object part : secretParts) {
            if (part != null) {
                addSecret(part.toString());
            }
        }
    }

    /** Adds a query parameter; its value is replaced by {@value #MASK} in returned URLs. */
    void queryParam(String name, String value) {
        queryParams.put(name, value);
        addSecret(value);
    }

    Map<String, String> headers() {
        return headers;
    }

    Map<String, Object> queryParams() {
        return queryParams;
    }

    /**
     * The URL with every injected query parameter's value masked; scheme, host, path and the
     * other parameters are kept so the endpoint stays identifiable.
     */
    String redactUrl(String url) {
        if (url == null || queryParams.isEmpty() && secrets.isEmpty()) {
            return url;
        }
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryParams.isEmpty()) {
            return redact(url);
        }
        int fragmentStart = url.indexOf('#', queryStart);
        String query = fragmentStart < 0
            ? url.substring(queryStart + 1) : url.substring(queryStart + 1, fragmentStart);
        String fragment = fragmentStart < 0 ? "" : url.substring(fragmentStart);
        List<String> pairs = new ArrayList<>();
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            pairs.add(queryParams.containsKey(decode(name)) ? name + "=" + MASK : pair);
        }
        return redact(url.substring(0, queryStart + 1) + String.join("&", pairs) + fragment);
    }

    private static String decode(String component) {
        try {
            return URLDecoder.decode(component, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return component;
        }
    }

    /** {@code text} with every injected secret value, raw or URL-encoded, masked. */
    String redact(String text) {
        if (text == null || secrets.isEmpty()) {
            return text;
        }
        List<String> longestFirst = new ArrayList<>(secrets);
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());
        String redacted = text;
        for (String secret : longestFirst) {
            redacted = redacted.replace(secret, MASK);
        }
        return redacted;
    }

    private void addSecret(String value) {
        if (value == null || value.length() < MIN_SCRUB_LENGTH) {
            return;
        }
        Set<String> forms = new LinkedHashSet<>();
        forms.add(value);
        forms.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        forms.add(value.replace(" ", "%20"));
        secrets.addAll(forms);
    }
}
