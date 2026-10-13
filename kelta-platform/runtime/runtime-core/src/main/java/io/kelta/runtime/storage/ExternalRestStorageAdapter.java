package io.kelta.runtime.storage;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.AggregationSpec;
import io.kelta.runtime.query.FilterCondition;
import io.kelta.runtime.query.FilterOperator;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.PaginationMetadata;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.query.SortDirection;
import io.kelta.runtime.query.SortField;
import io.kelta.runtime.storage.RestExecutor.RestRequest;
import io.kelta.runtime.storage.RestExecutor.RestResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link StorageAdapter} backed by a remote REST API instead of a Kelta physical table.
 *
 * <p>Each collection that selects {@code adapterType=external-rest} carries its connection
 * details in {@code storageConfig().adapterConfig()}:
 * <ul>
 *   <li>{@code baseUrl}     — required, e.g. {@code https://api.example.com}</li>
 *   <li>{@code path}        — collection resource path; default {@code "/" + collection.name()}</li>
 *   <li>{@code dataPath}    — key holding the array in a list response (e.g. {@code "data"});
 *                            omit when the body is itself a JSON array</li>
 *   <li>{@code totalPath}   — key holding the total count (e.g. {@code "total"}); optional</li>
 *   <li>{@code idAttribute} — remote field mapped to the record id; default {@code "id"}</li>
 *   <li>{@code pageParam} / {@code sizeParam} — query-param names for pagination;
 *                            default {@code page} / {@code pageSize}</li>
 *   <li>{@code bearerToken} — optional; sent as {@code Authorization: Bearer <token>}</li>
 * </ul>
 *
 * <p>Read/write map to {@code GET/POST/PUT/DELETE} on {@code baseUrl + path[ + "/" + id]}.
 * Pagination, sort, and {@code EQ} filters are pushed down as query params (best-effort —
 * richer operators are not translated, since remote query languages vary). Schema operations
 * are no-ops (the remote system owns its schema) and {@code aggregate} is unsupported.
 *
 * <p>Not annotated as a Spring bean: it is instantiated with a concrete {@link RestExecutor}
 * by the wiring slice. The {@link DispatchingStorageAdapter} discovers it via the
 * {@link ExternalStorageAdapter} marker.
 *
 * @since 1.0.0
 */
public class ExternalRestStorageAdapter implements ExternalStorageAdapter {

    public static final String STORAGE_TYPE = "external-rest";

    /** Response-cache safety cap: beyond this many entries the cache is cleared. */
    static final int MAX_CACHE_ENTRIES = 1000;

    private final RestExecutor executor;
    private final ObjectMapper objectMapper;
    private final CredentialProvider credentialProvider;
    private final java.util.function.LongSupplier clockMillis;

    /** GET response cache — populated only for collections with {@code cacheTtlSeconds > 0}. */
    private final java.util.concurrent.ConcurrentMap<String, CachedResponse> responseCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** One token bucket per baseUrl — only for collections with {@code rateLimitPerSecond > 0}. */
    private final java.util.concurrent.ConcurrentMap<String, TokenBucket> buckets =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ExternalRestStorageAdapter(RestExecutor executor, ObjectMapper objectMapper) {
        this(executor, objectMapper, null);
    }

    public ExternalRestStorageAdapter(RestExecutor executor, ObjectMapper objectMapper,
                                      CredentialProvider credentialProvider) {
        this(executor, objectMapper, credentialProvider, System::currentTimeMillis);
    }

    /** Test seam: inject the clock driving cache TTLs and rate-limit refills. */
    ExternalRestStorageAdapter(RestExecutor executor, ObjectMapper objectMapper,
                               CredentialProvider credentialProvider,
                               java.util.function.LongSupplier clockMillis) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.credentialProvider = credentialProvider;
        this.clockMillis = clockMillis;
    }

    @Override
    public String storageType() {
        return STORAGE_TYPE;
    }

    // --- read ---------------------------------------------------------------

    @Override
    public QueryResult query(CollectionDefinition definition, QueryRequest request) {
        RestConfig cfg = RestConfig.from(definition);
        String url = cfg.collectionUrl() + buildQueryString(cfg, request);
        RestResponse response = send("GET", url, cfg, null);
        if (response.status() < 200 || response.status() >= 300) {
            throw new StorageException("External REST query failed (" + response.status()
                    + ") for collection: " + definition.name());
        }

        Object root = parse(response.body());
        List<Map<String, Object>> records = extractRecords(root, cfg);

        Pagination pagination = request != null && request.pagination() != null
                ? request.pagination()
                : Pagination.defaults();
        long total = extractTotal(root, cfg).orElse((long) records.size());
        int totalPages = pagination.pageSize() > 0
                ? (int) Math.ceil((double) total / pagination.pageSize())
                : 0;
        PaginationMetadata metadata =
                new PaginationMetadata(total, pagination.pageNumber(), pagination.pageSize(), totalPages);
        return new QueryResult(records, metadata);
    }

    @Override
    public Optional<Map<String, Object>> getById(CollectionDefinition definition, String id) {
        RestConfig cfg = RestConfig.from(definition);
        RestResponse response = send("GET", cfg.recordUrl(id), cfg, null);
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new StorageException("External REST getById failed (" + response.status()
                    + ") for collection: " + definition.name());
        }
        return Optional.of(toRecord(unwrapSingle(parse(response.body()), cfg), cfg));
    }

    // --- write --------------------------------------------------------------

    @Override
    public Map<String, Object> create(CollectionDefinition definition, Map<String, Object> data) {
        RestConfig cfg = RestConfig.from(definition);
        RestResponse response = send("POST", cfg.collectionUrl(), cfg, write(data));
        if (response.status() < 200 || response.status() >= 300) {
            throw new StorageException("External REST create failed (" + response.status()
                    + ") for collection: " + definition.name());
        }
        return toRecord(unwrapSingle(parse(response.body()), cfg), cfg);
    }

    @Override
    public Optional<Map<String, Object>> update(CollectionDefinition definition, String id, Map<String, Object> data) {
        RestConfig cfg = RestConfig.from(definition);
        RestResponse response = send("PUT", cfg.recordUrl(id), cfg, write(data));
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new StorageException("External REST update failed (" + response.status()
                    + ") for collection: " + definition.name());
        }
        return Optional.of(toRecord(unwrapSingle(parse(response.body()), cfg), cfg));
    }

    @Override
    public boolean delete(CollectionDefinition definition, String id) {
        RestConfig cfg = RestConfig.from(definition);
        RestResponse response = send("DELETE", cfg.recordUrl(id), cfg, null);
        if (response.status() == 404) {
            return false;
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new StorageException("External REST delete failed (" + response.status()
                    + ") for collection: " + definition.name());
        }
        return true;
    }

    @Override
    public boolean isUnique(CollectionDefinition definition, String fieldName, Object value, String excludeId) {
        QueryRequest probe = new QueryRequest(
                new Pagination(1, 50),
                List.of(),
                List.of(),
                List.of(new FilterCondition(fieldName, FilterOperator.EQ, value)));
        for (Map<String, Object> record : query(definition, probe).data()) {
            Object id = record.get("id");
            if (id != null && !id.toString().equals(excludeId)) {
                return false;
            }
        }
        return true;
    }

    // --- schema (remote system owns it) ------------------------------------

    @Override
    public void initializeCollection(CollectionDefinition definition) {
        // No-op: the external system owns its schema.
    }

    @Override
    public void updateCollectionSchema(CollectionDefinition oldDefinition, CollectionDefinition newDefinition) {
        // No-op: the external system owns its schema.
    }

    @Override
    public Map<String, Object> aggregate(CollectionDefinition definition,
                                         List<FilterCondition> filters,
                                         List<AggregationSpec> specs) {
        throw new UnsupportedOperationException("Aggregation is not supported by the external-rest adapter");
    }

    // --- helpers ------------------------------------------------------------

    private RestResponse send(String method, String url, RestConfig cfg, String body) {
        boolean isGet = "GET".equals(method);
        boolean cacheable = isGet && cfg.cacheTtlSeconds() > 0;

        if (cacheable) {
            CachedResponse cached = responseCache.get(url);
            if (cached != null && cached.expiresAtMillis() > clockMillis.getAsLong()) {
                return cached.response();
            }
        }

        if (cfg.rateLimitPerSecond() > 0) {
            TokenBucket bucket = buckets.computeIfAbsent(cfg.baseUrl(),
                    k -> new TokenBucket(cfg.rateLimitPerSecond(), clockMillis.getAsLong()));
            if (!bucket.tryAcquire(clockMillis.getAsLong())) {
                throw new StorageException("External REST rate limit exceeded for " + cfg.baseUrl()
                        + " (" + cfg.rateLimitPerSecond() + "/s)");
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        applyAuth(headers, cfg);
        RestResponse response;
        try {
            response = executor.exchange(new RestRequest(method, url, headers, body));
        } catch (RuntimeException e) {
            throw new StorageException("External REST transport failure: " + method + " " + url
                    + (e.getMessage() != null ? " (" + e.getMessage() + ")" : ""), e);
        }

        if (cacheable && response.status() >= 200 && response.status() < 300) {
            if (responseCache.size() >= MAX_CACHE_ENTRIES) {
                responseCache.clear();
            }
            responseCache.put(url, new CachedResponse(response,
                    clockMillis.getAsLong() + cfg.cacheTtlSeconds() * 1000L));
        } else if (!isGet) {
            // A write through this adapter makes cached reads for the same
            // remote resource stale — drop them.
            invalidateCache(cfg);
        }
        return response;
    }

    /** Drops every cached GET under the collection's remote URL. */
    private void invalidateCache(RestConfig cfg) {
        String prefix = cfg.collectionUrl();
        responseCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private record CachedResponse(RestResponse response, long expiresAtMillis) {
    }

    /**
     * Minimal token bucket: capacity = one second's worth of permits, refilled
     * continuously. Dependency-free — runtime-core has no Caffeine/Guava.
     */
    static final class TokenBucket {
        private final double ratePerSecond;
        private double tokens;
        private long lastRefillMillis;

        TokenBucket(double ratePerSecond, long nowMillis) {
            this.ratePerSecond = ratePerSecond;
            this.tokens = ratePerSecond;
            this.lastRefillMillis = nowMillis;
        }

        synchronized boolean tryAcquire(long nowMillis) {
            double refill = (nowMillis - lastRefillMillis) / 1000.0 * ratePerSecond;
            if (refill > 0) {
                tokens = Math.min(ratePerSecond, tokens + refill);
                lastRefillMillis = nowMillis;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /**
     * Apply auth to the request headers. A vault {@code credentialRef} (resolved to a
     * decrypted {@link ResolvedCredential}) takes precedence; the inline {@code bearerToken}
     * remains as a fallback for simple/test configs. Auth-by-type mirrors the HTTP-callout
     * handler so connector credentials behave identically to flow callouts.
     */
    private void applyAuth(Map<String, String> headers, RestConfig cfg) {
        if (cfg.credentialRef() != null && !cfg.credentialRef().isBlank() && credentialProvider != null) {
            ResolvedCredential cred = credentialProvider.resolve(cfg.credentialRef())
                    .orElseThrow(() -> new StorageException("Credential not found: " + cfg.credentialRef()));
            switch (cred.type()) {
                case "bearer_token" -> putIfPresent(headers, "Authorization", "Bearer ", cred.secret("token"));
                case "basic_auth" -> {
                    Object user = cred.secret("username");
                    Object pass = cred.secret("password");
                    if (user != null && pass != null) {
                        String encoded = Base64.getEncoder().encodeToString(
                                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                        headers.put("Authorization", "Basic " + encoded);
                    }
                }
                case "api_key" -> {
                    Object value = cred.secret("value");
                    Object headerName = cred.metadata("headerName");
                    Object prefix = cred.metadata("prefix");
                    if (value != null && headerName != null) {
                        headers.put(headerName.toString(), (prefix == null ? "" : prefix.toString()) + value);
                    }
                }
                case "oauth2_client_credentials", "oauth2_authorization_code" ->
                        putIfPresent(headers, "Authorization", "Bearer ", cred.secret("accessToken"));
                default -> { /* smtp/custom: nothing to apply to an HTTP request */ }
            }
            return;
        }
        if (cfg.bearerToken() != null && !cfg.bearerToken().isBlank()) {
            headers.put("Authorization", "Bearer " + cfg.bearerToken());
        }
    }

    private static void putIfPresent(Map<String, String> headers, String name, String prefix, Object value) {
        if (value != null) {
            headers.put(name, prefix + value);
        }
    }

    private String buildQueryString(RestConfig cfg, QueryRequest request) {
        if (request == null) {
            return "";
        }
        StringJoiner params = new StringJoiner("&");
        if (request.pagination() != null) {
            params.add(cfg.pageParam() + "=" + request.pagination().pageNumber());
            params.add(cfg.sizeParam() + "=" + request.pagination().pageSize());
        }
        if (request.sorting() != null && !request.sorting().isEmpty()) {
            StringJoiner sort = new StringJoiner(",");
            for (SortField field : request.sorting()) {
                sort.add(field.direction() == SortDirection.DESC ? "-" + field.fieldName() : field.fieldName());
            }
            params.add("sort=" + encode(sort.toString()));
        }
        if (request.filters() != null) {
            for (FilterCondition filter : request.filters()) {
                // Only equality pushes down cleanly across arbitrary REST backends.
                if (filter.operator() == FilterOperator.EQ && filter.value() != null) {
                    params.add(encode(filter.fieldName()) + "=" + encode(filter.value().toString()));
                }
            }
        }
        String qs = params.toString();
        return qs.isEmpty() ? "" : "?" + qs;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRecords(Object root, RestConfig cfg) {
        Object listNode = root;
        if (cfg.dataPath() != null && root instanceof Map<?, ?> map) {
            listNode = map.get(cfg.dataPath());
        } else if (cfg.dataPath() == null && root instanceof Map<?, ?> map && map.get("data") instanceof List) {
            listNode = map.get("data");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        if (listNode instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> obj) {
                    records.add(toRecord((Map<String, Object>) obj, cfg));
                }
            }
        }
        return records;
    }

    private Optional<Long> extractTotal(Object root, RestConfig cfg) {
        if (cfg.totalPath() != null && root instanceof Map<?, ?> map) {
            Object total = map.get(cfg.totalPath());
            if (total instanceof Number n) {
                return Optional.of(n.longValue());
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapSingle(Object root, RestConfig cfg) {
        if (root instanceof Map<?, ?> map) {
            // Allow a { "data": { ... } } envelope on single-resource responses.
            Object data = cfg.dataPath() != null ? map.get(cfg.dataPath()) : map.get("data");
            if (data instanceof Map<?, ?> inner) {
                return (Map<String, Object>) inner;
            }
            return (Map<String, Object>) map;
        }
        throw new StorageException("External REST response was not a JSON object");
    }

    /** Map a remote object to a Kelta record, ensuring the {@code id} key is populated. */
    private Map<String, Object> toRecord(Map<String, Object> remote, RestConfig cfg) {
        Map<String, Object> record = new LinkedHashMap<>(remote);
        Object id = record.get(cfg.idAttribute());
        if (id != null) {
            record.put("id", id.toString());
        }
        return record;
    }

    private Object parse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (RuntimeException e) {
            throw new StorageException("Failed to parse external REST response", e);
        }
    }

    private String write(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (RuntimeException e) {
            throw new StorageException("Failed to serialize record for external REST request", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Parsed view of a collection's external-REST {@code adapterConfig}. */
    private record RestConfig(
            String baseUrl,
            String path,
            String dataPath,
            String totalPath,
            String idAttribute,
            String pageParam,
            String sizeParam,
            String bearerToken,
            String credentialRef,
            long cacheTtlSeconds,
            double rateLimitPerSecond) {

        static RestConfig from(CollectionDefinition definition) {
            Map<String, String> cfg = definition.storageConfig() != null
                    ? definition.storageConfig().adapterConfig()
                    : Map.of();
            String baseUrl = cfg.get("baseUrl");
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new StorageException(
                        "external-rest collection '" + definition.name() + "' is missing adapterConfig.baseUrl");
            }
            String path = cfg.getOrDefault("path", "/" + definition.name());
            return new RestConfig(
                    stripTrailingSlash(baseUrl),
                    path,
                    cfg.get("dataPath"),
                    cfg.get("totalPath"),
                    cfg.getOrDefault("idAttribute", "id"),
                    cfg.getOrDefault("pageParam", "page"),
                    cfg.getOrDefault("sizeParam", "pageSize"),
                    cfg.get("bearerToken"),
                    cfg.get("credentialRef"),
                    parseNonNegativeLong(cfg.get("cacheTtlSeconds"), definition.name(), "cacheTtlSeconds"),
                    parseNonNegativeDouble(cfg.get("rateLimitPerSecond"), definition.name(), "rateLimitPerSecond"));
        }

        private static long parseNonNegativeLong(String raw, String collection, String key) {
            if (raw == null || raw.isBlank()) {
                return 0L;
            }
            try {
                long value = Long.parseLong(raw.trim());
                return Math.max(0L, value);
            } catch (NumberFormatException e) {
                throw new StorageException("external-rest collection '" + collection
                        + "' has a non-numeric adapterConfig." + key + ": " + raw);
            }
        }

        private static double parseNonNegativeDouble(String raw, String collection, String key) {
            if (raw == null || raw.isBlank()) {
                return 0d;
            }
            try {
                double value = Double.parseDouble(raw.trim());
                return Math.max(0d, value);
            } catch (NumberFormatException e) {
                throw new StorageException("external-rest collection '" + collection
                        + "' has a non-numeric adapterConfig." + key + ": " + raw);
            }
        }

        String collectionUrl() {
            return baseUrl + (path.startsWith("/") ? path : "/" + path);
        }

        String recordUrl(String id) {
            return collectionUrl() + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        }

        private static String stripTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }
}
