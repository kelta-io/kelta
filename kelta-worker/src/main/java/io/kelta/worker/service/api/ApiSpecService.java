package io.kelta.worker.service.api;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.module.integration.api.ApiSpec;
import io.kelta.runtime.module.integration.api.OpenApiParseException;
import io.kelta.runtime.module.integration.api.OpenApiSpecParser;
import io.kelta.runtime.module.integration.api.ParsedSpec;
import io.kelta.runtime.outbound.OutboundHttpClient;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.worker.repository.ApiOperationRepository;
import io.kelta.worker.repository.ApiOperationRepository.OperationSyncResult;
import io.kelta.worker.repository.ApiSpecRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the import lifecycle for an OpenAPI spec:
 * <ol>
 *   <li>Fetch the document (URL) or accept inline JSON / YAML</li>
 *   <li>Parse + validate via {@link OpenApiSpecParser}</li>
 *   <li>Insert (first-time) or update (re-import) the {@code api_spec} row,
 *       bumping {@code revision} and the spec hash</li>
 *   <li>Sync the operation index — adds new, updates changed, deprecates
 *       removed</li>
 * </ol>
 *
 * Storage is wrapped in {@code TenantContext.callWithTenant(...)} so the V127
 * RLS policies engage on every read and write.
 *
 * <p>A {@code sourceUrl} is tenant-supplied, so it is fetched through {@link OutboundHttpClient}
 * with the shared {@link OutboundUrlGuard}: non-public addresses are refused unless
 * allow-listed, each redirect hop is re-validated (at most {@link #MAX_REDIRECTS}), and the fetch
 * is bounded by {@link #FETCH_TIMEOUT} overall and {@link #MAX_SPEC_BYTES}.
 */
@Service
public class ApiSpecService {

    private static final Logger log = LoggerFactory.getLogger(ApiSpecService.class);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);
    /** Large public APIs publish specs of several MB; anything above this is refused. */
    static final int MAX_SPEC_BYTES = 10 * 1024 * 1024;
    static final int MAX_REDIRECTS = 3;
    private static final String SPEC_ACCEPT = "application/json, application/yaml, text/yaml";

    private final OpenApiSpecParser parser;
    private final ApiSpecRepository specRepository;
    private final ApiOperationRepository operationRepository;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;
    private final OutboundHttpClient specFetcher;

    public ApiSpecService(OpenApiSpecParser parser,
                          ApiSpecRepository specRepository,
                          ApiOperationRepository operationRepository,
                          ObjectMapper objectMapper,
                          OutboundUrlGuard outboundUrlGuard) {
        this.parser = parser;
        this.specRepository = specRepository;
        this.operationRepository = operationRepository;
        this.objectMapper = objectMapper;
        this.specFetcher = new OutboundHttpClient(outboundUrlGuard, new OutboundHttpClient.Limits(
                CONNECT_TIMEOUT, FETCH_TIMEOUT, MAX_SPEC_BYTES, MAX_REDIRECTS));
    }

    /**
     * Imports a new spec or re-imports an existing one (matched by name within
     * the tenant). Returns the resulting record.
     */
    public ImportResult importSpec(ImportRequest req, String tenantId, String userId) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String raw = resolveRaw(req);
        String format = detectFormat(raw, req.rawFormat());

        ParsedSpec parsed;
        try {
            parsed = parser.parse(raw);
        } catch (OpenApiParseException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenApiParseException("Failed to parse spec: " + e.getMessage(), e);
        }

        String hash = parser.hash(raw);

        return TenantContext.callWithTenant(tenantId, () -> {
            Optional<ApiSpec> existing = specRepository.findByName(req.name(), tenantId);
            ApiSpec saved;
            OperationSyncResult diff;
            if (existing.isPresent()) {
                ApiSpec prior = existing.get();
                if (prior.specHash().equals(hash)) {
                    log.debug("Spec '{}' unchanged (hash match); skipping", req.name());
                    diff = new OperationSyncResult(0, 0, 0);
                    saved = prior;
                } else {
                    ApiSpec next = new ApiSpec(
                        prior.id(), tenantId, prior.name(),
                        req.description() != null ? req.description() : prior.description(),
                        parsed.specVersion(), parsed.apiTitle(), parsed.apiVersion(),
                        parsed.baseUrl(), parsed.servers(), parsed.securitySchemes(),
                        req.sourceType(), req.sourceUrl(),
                        raw, format, parsed.parsedSpec(),
                        hash, prior.revision() + 1, true, Instant.now());
                    specRepository.updateForReimport(next, userId);
                    diff = operationRepository.syncOperations(tenantId, prior.id(),
                        parsed.operations());
                    saved = next;
                    log.info("Re-imported spec '{}' (rev {}): +{} ~{} -{}",
                        prior.name(), next.revision(), diff.added(), diff.changed(),
                        diff.removed());
                }
            } else {
                ApiSpec next = new ApiSpec(
                    UUID.randomUUID().toString(), tenantId, req.name(), req.description(),
                    parsed.specVersion(), parsed.apiTitle(), parsed.apiVersion(),
                    parsed.baseUrl(), parsed.servers(), parsed.securitySchemes(),
                    req.sourceType(), req.sourceUrl(),
                    raw, format, parsed.parsedSpec(),
                    hash, 1, true, Instant.now());
                specRepository.insert(next, userId);
                diff = operationRepository.syncOperations(tenantId, next.id(),
                    parsed.operations());
                saved = next;
                log.info("Imported new spec '{}' with {} operations",
                    next.name(), parsed.operations().size());
            }
            return new ImportResult(saved, diff);
        });
    }

    public List<ApiSpec> listActive(String tenantId) {
        return TenantContext.callWithTenant(tenantId, () -> specRepository.listActive(tenantId));
    }

    public Optional<ApiSpec> findById(String id, String tenantId) {
        return TenantContext.callWithTenant(tenantId, () -> specRepository.findById(id, tenantId));
    }

    public void softDelete(String id, String tenantId, String userId) {
        TenantContext.runWithTenant(tenantId, () -> specRepository.softDelete(id, tenantId, userId));
    }

    // -----------------------------------------------------------------------

    private String resolveRaw(ImportRequest req) {
        if ("URL".equalsIgnoreCase(req.sourceType())) {
            if (req.sourceUrl() == null || req.sourceUrl().isBlank()) {
                throw new IllegalArgumentException(
                    "sourceUrl is required when sourceType=URL");
            }
            return fetchSpec(req.sourceUrl());
        }
        if (req.raw() == null || req.raw().isBlank()) {
            throw new IllegalArgumentException(
                "raw spec body is required when sourceType is INLINE_JSON or INLINE_YAML");
        }
        return req.raw();
    }

    /**
     * Fetches a spec document from a tenant-supplied URL.
     *
     * @throws IllegalArgumentException when the URL may not be fetched (blank, not http(s),
     *         carries credentials, does not resolve, or resolves — directly or through a
     *         redirect — to a non-public address that is not allow-listed); nothing is sent
     * @throws OpenApiParseException when the fetch fails, is not 2xx, or exceeds the size cap
     */
    public String fetchSpec(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("Provide either 'raw' or 'sourceUrl'");
        }
        OutboundHttpClient.Response res;
        try {
            res = specFetcher.get(sourceUrl, Map.of("Accept", SPEC_ACCEPT));
        } catch (OutboundUrlGuard.RejectedUrlException e) {
            log.warn("Spec fetch refused for {}: {}", sourceUrl, e.getMessage());
            throw new IllegalArgumentException("sourceUrl refused: " + e.getMessage());
        } catch (OutboundHttpClient.OutboundRequestException e) {
            throw new OpenApiParseException(
                "Failed to fetch spec from " + sourceUrl + ": " + e.getMessage(), e);
        }
        if (!res.isSuccess()) {
            throw new OpenApiParseException("Failed to fetch spec: HTTP " + res.statusCode());
        }
        if (res.truncated()) {
            throw new OpenApiParseException(
                "Failed to fetch spec: the document exceeds " + MAX_SPEC_BYTES + " bytes");
        }
        return res.bodyAsString();
    }

    private String detectFormat(String raw, String hint) {
        if (hint != null && !hint.isBlank()) return hint.toLowerCase();
        String trimmed = raw.stripLeading();
        if (trimmed.startsWith("{")) return "json";
        return "yaml";
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

    /**
     * Body shape expected by the import / re-import endpoints. Exactly one of
     * {@code raw} or {@code sourceUrl} should be supplied (matching
     * {@code sourceType}).
     */
    public record ImportRequest(
        String name,
        String description,
        String sourceType,
        String sourceUrl,
        String raw,
        String rawFormat
    ) {
    }

    public record ImportResult(ApiSpec spec, OperationSyncResult diff) {
    }
}
