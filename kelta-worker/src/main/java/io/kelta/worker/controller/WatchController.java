package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.Watch;
import io.kelta.worker.repository.WatchRepository;
import io.kelta.worker.repository.WatchTarget;
import io.kelta.worker.repository.WatchTargetRepository;
import io.kelta.worker.service.availability.WatchCriteria;
import io.kelta.worker.service.availability.AlertDispatchService;
import io.kelta.worker.service.billing.EntitlementService;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import io.kelta.worker.interceptor.SelfScopedController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Member-facing watch API.
 *
 * <p>{@code /api/watches/**} is a {@code static-} gateway route, so the gateway
 * applies only the blanket {@code API_ACCESS} check — <b>all</b> member scoping is
 * enforced here.
 *
 * <p><b>A foreign watch id returns 404, not 403.</b> 403 would confirm the id
 * exists, letting a member enumerate other members' watches by probing ids. The
 * two cases are deliberately indistinguishable.
 *
 * <p>Writes go through {@link QueryEngine} rather than straight to the repository
 * so the platform's hooks fire: {@code WatchGuardHook} (owner guard) and
 * {@code MemberEntitlementQuotaHook} (plan limits). Bypassing it would silently
 * skip both. This controller additionally pre-checks the quota so a member at
 * their limit gets an actionable message instead of a bare validation error.
 *
 * <p>The generic dynamic route reaches the same collection, which is why
 * {@code WatchGuardHook} exists — this controller is the pleasant door, not the
 * only one.
 *
 * <p>Support staff (INTERNAL, holding {@link #SUPPORT_PERMISSION}) calling {@code list}/{@code get}
 * with no {@code memberId} are handed the tenant's full, unfiltered view — delegated verbatim to
 * {@link DynamicCollectionRouter} so paging, filtering, sorting and {@code meta.totalCount} all
 * behave exactly like every other collection, rather than being reimplemented here.
 */
@RestController
@RequestMapping("/api/watches")
public class WatchController implements SelfScopedController {

    private static final Logger log = LoggerFactory.getLogger(WatchController.class);

    static final String COLLECTION = "watches";
    /** Entitlement key capping how many active watches a member may hold. */
    static final String ENTITLEMENT_MAX_WATCHES = "maxActiveWatches";
    /** Entitlement key listing the alert channels a member's plan permits. */
    static final String ENTITLEMENT_CHANNELS = "channels";
    /** Permission letting internal staff act on a named member's behalf (support). */
    static final String SUPPORT_PERMISSION = "MANAGE_DATA";

    /** Collection holding watchable things, for the promotion path. */
    static final String TARGET_COLLECTION = "watch-targets";
    /** Longest name accepted when promoting; the column is varchar(255). */
    private static final int MAX_PROMOTED_NAME = 255;

    private final WatchRepository watchRepository;
    private final WatchTargetRepository targetRepository;
    private final QueryEngine queryEngine;
    private final CollectionRegistry collectionRegistry;
    private final EntitlementService entitlementService;
    private final UserIdResolver userIdResolver;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;
    private final ObjectMapper objectMapper;
    private final DynamicCollectionRouter dynamicCollectionRouter;
    /**
     * Sources a member may promote a target for, from
     * {@code kelta.watch.promotable-sources}.
     *
     * <p>Empty by default, which turns promotion off. It has to be opt-in: creating a
     * target is creating something the poller will be asked to poll, so an open door
     * here lets any member with API_ACCESS mint arbitrary rows in another system's
     * name.
     */
    private final Set<String> promotableSources;

    public WatchController(WatchRepository watchRepository,
                           WatchTargetRepository targetRepository,
                           QueryEngine queryEngine,
                           CollectionRegistry collectionRegistry,
                           EntitlementService entitlementService,
                           UserIdResolver userIdResolver,
                           CerbosPermissionResolver permissionResolver,
                           BootstrapRepository bootstrapRepository,
                           ObjectMapper objectMapper,
                           DynamicCollectionRouter dynamicCollectionRouter,
                           @Value("${kelta.watch.promotable-sources:}") String promotableSources) {
        this.watchRepository = watchRepository;
        this.targetRepository = targetRepository;
        this.queryEngine = queryEngine;
        this.collectionRegistry = collectionRegistry;
        this.entitlementService = entitlementService;
        this.userIdResolver = userIdResolver;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
        this.objectMapper = objectMapper;
        this.dynamicCollectionRouter = dynamicCollectionRouter;
        this.promotableSources = promotableSources == null || promotableSources.isBlank()
                ? Set.of()
                : Arrays.stream(promotableSources.split(","))
                        .map(String::trim)
                        .filter(v -> !v.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The caller's own watches. Support staff may name another member explicitly, or — naming
     * none — fall through to the tenant's full JSON:API view (paging, filtering, sorting,
     * {@code meta.totalCount}), delegated to {@link DynamicCollectionRouter} rather than
     * reimplemented here.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String memberId,
                                    @RequestParam(required = false) MultiValueMap<String, String> params,
                                    HttpServletRequest request) {
        String tenantId = requireTenant();
        if (!isPortalCaller(request) && (memberId == null || memberId.isBlank())) {
            if (!hasSupportPermission(request, tenantId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        SUPPORT_PERMISSION + " permission required");
            }
            return dynamicCollectionRouter.list(COLLECTION, params, request);
        }
        String subject = resolveSubject(request, tenantId, memberId);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Watch watch : watchRepository.findByMember(tenantId, subject)) {
            items.add(summarize(tenantId, watch));
        }
        return ResponseEntity.ok(Map.of("data", items));
    }

    /**
     * A single watch. Support staff resolve any row in the tenant, delegated to
     * {@link DynamicCollectionRouter}; everyone else only their own — a foreign id is 404, not
     * 403, for the same enumeration-safety reason as {@link #requireOwnWatch}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id,
                                    @RequestParam(required = false) MultiValueMap<String, String> params,
                                    HttpServletRequest request) {
        String tenantId = requireTenant();
        if (hasSupportPermission(request, tenantId)) {
            return dynamicCollectionRouter.get(COLLECTION, id, params, request);
        }
        String subject = requireActor(request, tenantId);
        Watch watch = requireOwnWatch(tenantId, id, subject);
        return ResponseEntity.ok(Map.of("data", summarize(tenantId, watch)));
    }

    /** Creates a watch owned by the caller. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateWatchRequest body,
                                                      HttpServletRequest request) {
        String tenantId = requireTenant();
        String subject = resolveSubject(request, tenantId,
                body == null ? null : body.memberId());
        // Quota BEFORE resolving the target, because resolving may create one.
        // The other order leaks: a member at their limit gets a 422 and still leaves a
        // watch_target behind, so retrying in a loop mints unlimited rows — each one
        // something the poller can be asked to poll — while never creating a watch.
        //
        // Still a pre-check for a nicer message, not the guard; the quota hook enforces
        // it on the way through.
        int limit = entitlementService.intLimit(tenantId, subject, ENTITLEMENT_MAX_WATCHES,
                Integer.MAX_VALUE);
        if (limit != Integer.MAX_VALUE) {
            int used = watchRepository.countByMemberAndStatus(tenantId, subject,
                    Watch.STATUS_ACTIVE);
            if (used >= limit) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "You have reached your plan limit (" + used + "/" + limit
                                + "). Upgrade your plan to add more watches.");
            }
        }

        WatchTarget target = resolveTarget(tenantId, body);
        if (!target.active()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Target is not currently watchable");
        }

        String criteria = validateCriteria(body.criteria());
        List<String> channels = intersectChannels(tenantId, subject, body.channels());

        Map<String, Object> data = new LinkedHashMap<>();
        // Direct queryEngine.create must set tenantId itself — the JSON:API layer
        // injects it on the HTTP path, and without it the NOT NULL is violated.
        data.put("tenantId", tenantId);
        data.put("memberId", subject);
        data.put("targetId", target.id());
        data.put("criteria", criteriaStructure(criteria));
        data.put("channels", channels);
        data.put("status", Watch.STATUS_ACTIVE);
        if (body.expiresAt() != null) {
            data.put("expiresAt", body.expiresAt());
        }

        Map<String, Object> created = queryEngine.create(definition(), data);
        log.info("Member {} started watching target {} in tenant {}",
                subject, target.id(), tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", created));
    }

    /**
     * Updates target, criteria, channels, or status (pause/resume) on a watch.
     *
     * <p>Normally the caller's own watch; support staff holding
     * {@link #SUPPORT_PERMISSION} may name a member, exactly as {@code list} and
     * {@code create} already allow. Without that, correcting a member's watch
     * needs direct database access, which skips the entitlement and validation
     * guards below.
     */
    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id,
                                      @RequestBody UpdateWatchRequest body,
                                      HttpServletRequest request) {
        String tenantId = requireTenant();
        String subject = resolveSubject(request, tenantId,
                body == null ? null : body.memberId());
        Watch existing = requireOwnWatch(tenantId, id, subject);

        Map<String, Object> data = new LinkedHashMap<>();
        if (body != null && body.targetId() != null && !body.targetId().isBlank()) {
            // Same checks as create: an unknown or retired target must not be
            // reachable by re-pointing an existing watch either.
            WatchTarget target = targetRepository.findById(tenantId, body.targetId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Unknown target"));
            if (!target.active()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Target is not currently watchable");
            }
            data.put("targetId", target.id());
        }
        if (body != null && body.criteria() != null) {
            data.put("criteria", criteriaStructure(validateCriteria(body.criteria())));
        }
        if (body != null && body.channels() != null) {
            data.put("channels",
                    intersectChannels(tenantId, existing.memberId(), body.channels()));
        }
        if (body != null && body.status() != null) {
            data.put("status", requirePausableStatus(body.status()));
        }
        if (data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to update");
        }

        return queryEngine.update(definition(), id, data)
                .map(updated -> Map.of("data", (Object) updated))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
    }

    /** Deletes a watch — the caller's own, or a named member's for support staff. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @RequestParam(required = false) String memberId,
                                       HttpServletRequest request) {
        String tenantId = requireTenant();
        String subject = resolveSubject(request, tenantId, memberId);
        requireOwnWatch(tenantId, id, subject);

        queryEngine.delete(definition(), id);
        return ResponseEntity.noContent().build();
    }

    /** Alert count for one of the caller's watches. */
    @GetMapping("/{id}/alerts")
    public Map<String, Object> alerts(@PathVariable String id, HttpServletRequest request) {
        String tenantId = requireTenant();
        String subject = requireActor(request, tenantId);
        Watch watch = requireOwnWatch(tenantId, id, subject);

        return Map.of("data", Map.of(
                "watchId", watch.id(),
                "alertCount", watchRepository.countByMemberAndStatus(
                        tenantId, watch.memberId(), watch.status())));
    }

    // ------------------------------------------------------------- Helpers

    /**
     * Loads a watch the caller is allowed to act on.
     *
     * <p>A watch that does not exist and a watch belonging to someone else are
     * <b>both</b> 404: returning 403 for the latter would confirm the id is real
     * and turn this endpoint into an enumeration oracle.
     */
    private Watch requireOwnWatch(String tenantId, String id, String subject) {
        return watchRepository.findByMember(tenantId, subject).stream()
                .filter(w -> w.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
    }

    /**
     * Whose watches this request is about. Normally the caller; internal staff
     * holding {@link #SUPPORT_PERMISSION} may name a member for support work.
     */
    private String resolveSubject(HttpServletRequest request, String tenantId, String memberId) {
        String caller = requireActor(request, tenantId);
        if (memberId == null || memberId.isBlank() || memberId.equals(caller)) {
            return caller;
        }
        if (!hasSupportPermission(request, tenantId)) {
            // Do not reveal whether the named member exists.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted");
        }
        log.info("Support actor acting on watches of member {} in tenant {}", memberId, tenantId);
        return memberId;
    }

    /**
     * True when the caller is internal staff holding {@link #SUPPORT_PERMISSION}.
     * Same check shape as {@code TelehealthArchiveController.requireManageData}.
     * A PORTAL actor short-circuits to false before any lookup — a member is never
     * support staff, whatever their profile happens to grant.
     */
    boolean hasSupportPermission(HttpServletRequest request, String tenantId) {
        String userType = request.getHeader("X-User-Type");
        if (userType != null && "PORTAL".equalsIgnoreCase(userType)) {
            return false;
        }
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            return false;
        }
        return bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> SUPPORT_PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
    }

    /** True when the caller is a portal (member) actor, per the gateway-stamped identity. */
    private boolean isPortalCaller(HttpServletRequest request) {
        String userType = request.getHeader("X-User-Type");
        return userType != null && "PORTAL".equalsIgnoreCase(userType);
    }

    /** Rejects criteria the matcher could not act on, before it is stored. */
    private String validateCriteria(Object criteria) {
        String json = criteria == null ? null
                : criteria instanceof String s ? s
                : objectMapper.writeValueAsString(criteria);
        WatchCriteria.ParseResult parsed = WatchCriteria.parse(json, objectMapper);
        if (!parsed.isValid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid criteria: " + String.join("; ", parsed.errors()));
        }
        // Re-serialize through the parser so what is stored always carries the
        // current version stamp, whatever the client sent.
        return parsed.criteria().toJson(objectMapper);
    }

    /**
     * Narrows requested channels to the member's entitlement. Silently dropping
     * an unentitled channel is deliberate — the alternative is rejecting the whole
     * request over a channel the member cannot use anyway.
     */
    private List<String> intersectChannels(String tenantId, String memberId,
                                           List<String> requested) {
        List<String> entitled = entitlementService.listLimit(tenantId, memberId,
                ENTITLEMENT_CHANNELS);
        if (requested == null || requested.isEmpty()) {
            return entitled;
        }
        if (entitled.isEmpty()) {
            // Tenant is not gating channels -- except for the ones that cost money, which are
            // only ever granted on an affirmative entitlement (AlertDispatchService.BILLABLE_CHANNELS).
            // Accepting a billable channel here would also mislead the member: the watch would
            // claim SMS while dispatch declines to send it.
            List<String> free = requested.stream()
                    .filter(c -> !AlertDispatchService.BILLABLE_CHANNELS.contains(c))
                    .toList();
            if (free.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "None of the requested alert channels are available on your plan");
            }
            return free;
        }
        List<String> intersection = new ArrayList<>();
        for (String channel : requested) {
            if (entitled.contains(channel)) {
                intersection.add(channel);
            }
        }
        if (intersection.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "None of the requested alert channels are available on your plan");
        }
        return intersection;
    }

    /** Members may pause and resume; EXPIRED/FULFILLED are set by the platform. */
    private String requirePausableStatus(String status) {
        if (Watch.STATUS_ACTIVE.equals(status) || Watch.STATUS_PAUSED.equals(status)) {
            return status;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "status must be ACTIVE or PAUSED");
    }

    private Map<String, Object> summarize(String tenantId, Watch watch) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", watch.id());
        item.put("targetId", watch.targetId());
        item.put("status", watch.status());
        item.put("expiresAt", watch.expiresAt());
        item.put("criteria", watch.criteria());
        item.put("channels", watch.channels());
        targetRepository.findById(tenantId, watch.targetId()).ifPresent(target -> item.put(
                "target", Map.of(
                        "id", target.id(),
                        "name", target.name(),
                        "source", target.source(),
                        "category", String.valueOf(target.category()))));
        return item;
    }

    /**
     * Criteria as a structure, not JSON text. The storage adapter JSON-encodes
     * whatever it is handed, so passing the serialized form stored a JSON *string*
     * wrapping the object — which then read back as a textual node, silently
     * defeating criteria matching and channel selection.
     */
    private Map<String, Object> criteriaStructure(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException e) {
            // validateCriteria already re-serialized this from a parsed structure,
            // so failing here means a bug, not bad input.
            throw new IllegalStateException("criteria could not be re-read as a structure", e);
        }
    }

    /**
     * Finds the target a create request names, promoting it if the tenant allows.
     *
     * <p>Two ways in. {@code targetId} addresses an existing row directly. {@code source}
     * + {@code externalId} resolves by natural key and, when that source is promotable,
     * creates the row if it is missing — which is what lets a member watch something from
     * a catalog without an operator minting the target first.
     *
     * <p>Promotion is deliberately narrow. Only sources named in
     * {@code kelta.watch.promotable-sources} may be created, and the default is none:
     * creating a target is creating something the poller will be asked to poll, so an
     * open door lets any caller with API_ACCESS mint rows in another system's name.
     *
     * <p>Note what this does NOT verify: that the external id is real, or pollable. The
     * platform has no way to know — that belongs to whoever owns the catalog, and the
     * client is expected to offer only things it knows are watchable. A bogus id here
     * costs a watch that never fires, which is why the audit that reconciles targets
     * against their source matters.
     */
    private WatchTarget resolveTarget(String tenantId, CreateWatchRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "targetId, or source and externalId, is required");
        }
        if (body.targetId() != null && !body.targetId().isBlank()) {
            return targetRepository.findById(tenantId, body.targetId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Unknown target"));
        }

        String source = trimToNull(body.source());
        String externalId = trimToNull(body.externalId());
        if (source == null || externalId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "targetId, or source and externalId, is required");
        }

        Optional<WatchTarget> existing =
                targetRepository.findBySourceAndExternalId(tenantId, source, externalId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!promotableSources.contains(source)) {
            // 404 rather than 403: from the caller's side the target simply does not
            // exist, and whether this deployment permits promotion is not their business.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown target");
        }
        return promote(tenantId, source, externalId, body);
    }

    /**
     * Creates a watch target for a promotable source.
     *
     * <p>The unique index on {@code (tenant_id, source, external_id)} is the concurrency
     * control: two members watching the same facility at the same moment both reach here,
     * one wins, and the loser re-reads rather than failing. Checking first and then
     * creating would leave exactly that race open.
     */
    private WatchTarget promote(String tenantId, String source, String externalId,
                                CreateWatchRequest body) {
        // Validate the request before reaching for infrastructure: a missing name is the
        // caller's problem (400) and an unregistered collection is ours (500). Checking
        // the registry first would report our fault for their mistake.
        String name = trimToNull(body.name());
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name is required when creating a target");
        }
        if (name.length() > MAX_PROMOTED_NAME) {
            name = name.substring(0, MAX_PROMOTED_NAME);
        }

        CollectionDefinition targets = collectionRegistry.get(TARGET_COLLECTION);
        if (targets == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "watch-targets collection not registered");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        // Direct queryEngine.create must set tenantId itself, same as watches below.
        data.put("tenantId", tenantId);
        data.put("source", source);
        data.put("externalId", externalId);
        data.put("name", name);
        data.put("category", trimToNull(body.category()) != null ? body.category().trim() : source);
        data.put("active", true);

        try {
            queryEngine.create(targets, data);
        } catch (UniqueConstraintViolationException e) {
            log.debug("Target {}/{} was promoted concurrently in tenant {}; reusing it",
                    source, externalId, tenantId);
        }

        return targetRepository.findBySourceAndExternalId(tenantId, source, externalId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Promoted target could not be read back"));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private CollectionDefinition definition() {
        CollectionDefinition definition = collectionRegistry.get(COLLECTION);
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "watches collection not registered");
        }
        return definition;
    }

    private String requireTenant() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        return tenantId;
    }

    private String requireActor(HttpServletRequest request, String tenantId) {
        String identifier = request.getHeader("X-User-Id");
        if (identifier == null || identifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        String userId = userIdResolver.resolve(identifier, tenantId);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unresolvable identity");
        }
        return userId;
    }

    /**
     * Request body for {@code POST /api/watches}.
     *
     * <p>Identify the target either by {@code targetId}, or by {@code source} +
     * {@code externalId} to have it resolved — and created if the tenant permits
     * promotion for that source. {@code name} and {@code category} are only read
     * when a target is actually created.
     */
    public record CreateWatchRequest(String targetId, Object criteria, List<String> channels,
                                     String memberId, String expiresAt,
                                     String source, String externalId,
                                     String name, String category) {
    }

    /** Request body for {@code PATCH /api/watches/{id}}. */
    public record UpdateWatchRequest(String targetId, Object criteria, List<String> channels,
                                     String status, String memberId) {
    }
}
