package io.kelta.worker.controller;

import io.kelta.jsonapi.JsonApiResponseBuilder;
import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.repository.MailboxAccessRepository;
import io.kelta.worker.repository.MailboxAutoReplyDecisionRepository;
import io.kelta.worker.repository.MailboxEscalationRepository;
import io.kelta.worker.repository.MailboxRepository;
import io.kelta.worker.service.mailbox.SupportAutoReplySweep;
import tools.jackson.databind.ObjectMapper;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.mailbox.MailboxSecretService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Admin API for support mailboxes. Every endpoint requires {@code MANAGE_SUPPORT_MAILBOX}.
 *
 * <p>The {@code mailboxes} and {@code mailbox-access} system collections are read-only over
 * the generic API, so every mutation flows through here. That is not tidiness: creating a
 * mailbox has to mint a webhook key and a vault-stored HMAC secret and hand the secret back
 * exactly once, and granting access is a permission change that has to be validated and
 * audited. A generic {@code POST} does none of it.
 *
 * <p>This is a {@code static-} gateway route, so the gateway checks only blanket
 * {@code API_ACCESS} — every authorization decision below is this controller's own.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/support/mailboxes")
// Gated on the SAME property that creates EncryptionService and CredentialResolverImpl
// (both @ConditionalOnProperty "kelta.encryption.key"). Without the gate this component is a
// required dependency on beans that may not exist, and the whole worker fails to start in any
// environment without an encryption key. @ConditionalOnBean is deliberately not used here: it
// is order-sensitive during component scan and can drop the bean even when the dependency is
// later registered — see TenantEmailSettingsController for the same reasoning.
@ConditionalOnProperty(name = "kelta.encryption.key")
public class MailboxAdminController {

    private static final Logger log = LoggerFactory.getLogger(MailboxAdminController.class);

    private static final String PERMISSION = "MANAGE_SUPPORT_MAILBOX";
    private static final int MAX_PAGE = 200;

    private static final Set<String> PROVIDERS = Set.of(
            "SES_SNS", "SES_SNS_INLINE", "GENERIC_HMAC", "POSTMARK", "MAILGUN", "CLOUDMAILIN");
    private static final Set<String> ROLES = Set.of("VIEWER", "AGENT", "MANAGER");
    private static final Set<String> PRINCIPAL_TYPES = Set.of("USER", "GROUP");
    private static final Set<String> ESCALATION_LEVELS =
            Set.of("WARN", "BREACH", "BREACH_2", "BREACH_3");
    // SMS is absent: it is billable and nothing here resolves a per-tenant entitlement.
    private static final Set<String> ESCALATION_CHANNELS = Set.of("email", "push");

    /**
     * Deliberately permissive. This validates that a value is shaped like an address, not
     * that it is deliverable; RFC 5321 is far broader than the common regexes admit, and
     * rejecting a valid address here would make a mailbox impossible to create.
     */
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    private final MailboxRepository mailboxRepository;
    private final MailboxAccessRepository accessRepository;
    private final MailboxSecretService secretService;
    private final io.kelta.worker.service.mailbox.MailboxAccessGuard accessGuard;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;
    private final MailboxEscalationRepository escalationRepository;
    private final MailboxAutoReplyDecisionRepository decisionRepository;
    private final SupportAutoReplySweep autoReplySweep;
    private final ObjectMapper objectMapper;

    public MailboxAdminController(MailboxRepository mailboxRepository,
                                  MailboxAccessRepository accessRepository,
                                  MailboxSecretService secretService,
                                  io.kelta.worker.service.mailbox.MailboxAccessGuard accessGuard,
                                  CerbosPermissionResolver permissionResolver,
                                  BootstrapRepository bootstrapRepository,
                                  MailboxEscalationRepository escalationRepository,
                                  MailboxAutoReplyDecisionRepository decisionRepository,
                                  SupportAutoReplySweep autoReplySweep,
                                  ObjectMapper objectMapper) {
        this.mailboxRepository = mailboxRepository;
        this.accessRepository = accessRepository;
        this.secretService = secretService;
        this.accessGuard = accessGuard;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
        this.escalationRepository = escalationRepository;
        this.decisionRepository = decisionRepository;
        this.autoReplySweep = autoReplySweep;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- Mailbox CRUD

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request,
                                                    @RequestParam(defaultValue = "50") int limit,
                                                    @RequestParam(defaultValue = "0") int offset) {
        requirePermission(request);
        String tenantId = requireTenant();
        List<Map<String, Object>> records = mailboxRepository
                .list(tenantId, clamp(limit), Math.max(0, offset))
                .stream().map(this::project).toList();
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("mailboxes", records));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request, @PathVariable String id) {
        requirePermission(request);
        Map<String, Object> row = load(id, requireTenant());
        return ResponseEntity.ok(JsonApiResponseBuilder.single("mailboxes", id, project(row)));
    }

    /**
     * Creates a mailbox and returns the inbound signing secret.
     *
     * <p>The {@code inboundSecret} in this response is the only time the plaintext exists
     * outside the vault. It is not readable afterwards by any endpoint — losing it means
     * rotating, which is the correct trade: an API that can re-read a live signing secret
     * is a much worse thing to own than an admin who has to rotate one.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request,
                                                      @RequestBody Map<String, Object> body) {
        requirePermission(request);
        String tenantId = requireTenant();
        String actor = actor(request);

        Map<String, Object> attrs = normalize(attributes(body));
        requireText(attrs, "name");
        requireText(attrs, "address");
        validate(attrs);

        String address = (String) attrs.get("address");
        if (mailboxRepository.addressExists(tenantId, address, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A mailbox for " + address + " already exists");
        }

        String webhookKey = secretService.generateWebhookKey();
        String id = mailboxRepository.create(tenantId, webhookKey, attrs, actor);

        MailboxSecretService.MintedSecret minted =
                secretService.mint(tenantId, id, (String) attrs.get("name"), actor);
        mailboxRepository.rotateSecret(id, tenantId, minted.credentialId(), minted.hint(), 0, actor);

        Map<String, Object> row = mailboxRepository.findById(id, tenantId).orElseThrow();
        Map<String, Object> projected = new LinkedHashMap<>(project(row));
        projected.put("inboundSecret", minted.plaintext());
        projected.put("inboundSecretNotice",
                "Store this now — it cannot be retrieved again. Rotate if lost.");

        log.info("Mailbox {} created in tenant {} for {}", id, tenantId, address);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(JsonApiResponseBuilder.single("mailboxes", id, projected));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(HttpServletRequest request,
                                                      @PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        requirePermission(request);
        String tenantId = requireTenant();
        load(id, tenantId);

        Map<String, Object> attrs = normalize(attributes(body));
        validate(attrs);

        if (attrs.get("address") instanceof String addr
                && mailboxRepository.addressExists(tenantId, addr, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A mailbox for " + addr + " already exists");
        }

        mailboxRepository.update(id, tenantId, attrs, actor(request));
        Map<String, Object> row = mailboxRepository.findById(id, tenantId).orElseThrow();
        return ResponseEntity.ok(JsonApiResponseBuilder.single("mailboxes", id, project(row)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String id) {
        requirePermission(request);
        String tenantId = requireTenant();
        load(id, tenantId);
        // Threads, messages and access rows cascade from the mailbox FK. Deleting a mailbox
        // therefore destroys its correspondence history — the UI must make that explicit.
        mailboxRepository.delete(id, tenantId);
        log.info("Mailbox {} deleted in tenant {}", id, tenantId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- Secret rotation

    /**
     * Mints a new inbound secret and demotes the current one to the previous slot.
     *
     * <p>Both verify until the overlap expires, because a provider cannot switch its signing
     * key at the same instant we do. Without the overlap, every delivery in flight during the
     * switch fails signature verification and is dropped — and a dropped inbound message is a
     * lost customer email, not a retryable blip.
     */
    @PostMapping("/{id}/rotate-secret")
    public ResponseEntity<Map<String, Object>> rotateSecret(HttpServletRequest request,
                                                            @PathVariable String id) {
        requirePermission(request);
        String tenantId = requireTenant();
        Map<String, Object> row = load(id, tenantId);
        String actor = actor(request);

        // The secret already sitting in the previous slot is now two generations old and is
        // being displaced, so retire it rather than orphaning an active credential row.
        secretService.deactivate(tenantId, (String) row.get("inbound_prev_secret_credential_id"));

        MailboxSecretService.MintedSecret minted =
                secretService.mint(tenantId, id, String.valueOf(row.get("name")), actor);
        mailboxRepository.rotateSecret(id, tenantId, minted.credentialId(), minted.hint(),
                secretService.overlapMinutes(), actor);

        Map<String, Object> updated = mailboxRepository.findById(id, tenantId).orElseThrow();
        Map<String, Object> projected = new LinkedHashMap<>(project(updated));
        projected.put("inboundSecret", minted.plaintext());
        projected.put("inboundSecretNotice",
                "Store this now — it cannot be retrieved again. The previous secret keeps working for "
                        + secretService.overlapMinutes() + " minutes.");

        log.info("Mailbox {} inbound secret rotated in tenant {}", id, tenantId);
        return ResponseEntity.ok(JsonApiResponseBuilder.single("mailboxes", id, projected));
    }

    // ---------------------------------------------------------------- Membership

    @GetMapping("/{id}/access")
    public ResponseEntity<Map<String, Object>> listAccess(HttpServletRequest request,
                                                          @PathVariable String id) {
        requirePermission(request);
        String tenantId = requireTenant();
        load(id, tenantId);
        List<Map<String, Object>> records = accessRepository.listForMailbox(tenantId, id)
                .stream().map(this::projectAccess).toList();
        return ResponseEntity.ok(JsonApiResponseBuilder.collection("mailbox-access", records));
    }

    @PostMapping("/{id}/access")
    public ResponseEntity<Map<String, Object>> grantAccess(HttpServletRequest request,
                                                           @PathVariable String id,
                                                           @RequestBody Map<String, Object> body) {
        requirePermission(request);
        String tenantId = requireTenant();
        load(id, tenantId);

        Map<String, Object> attrs = attributes(body);
        String principalType = upper(attrs.get("principalType"));
        String principalId = text(attrs.get("principalId"));
        String role = upper(attrs.get("role"));

        if (!PRINCIPAL_TYPES.contains(principalType)) {
            throw badRequest("principalType must be one of " + PRINCIPAL_TYPES);
        }
        if (principalId == null) {
            throw badRequest("principalId is required");
        }
        if (!ROLES.contains(role)) {
            throw badRequest("role must be one of " + ROLES);
        }

        if ("USER".equals(principalType)) {
            // An admin will reasonably type an email here. Resolving it to the user id keeps the
            // grant matching what the console looks up, and keeps the value inside varchar(36),
            // which most real email addresses do not fit in.
            String resolved = accessGuard.resolveUserId(tenantId, principalId);
            if (resolved == null) {
                throw badRequest("No user in this tenant matches '" + principalId + "'");
            }
            principalId = resolved;
        }

        String accessId = accessRepository.grant(tenantId, id, principalType, principalId, role, actor(request));
        // Granting access is a permission change, so it belongs in the log whether or not
        // anyone is watching the audit trail today.
        log.info("Mailbox {} access granted to {} {} as {} in tenant {}",
                id, principalType, principalId, role, tenantId);

        Map<String, Object> row = accessRepository.findById(accessId, tenantId).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(JsonApiResponseBuilder.single("mailbox-access", accessId, projectAccess(row)));
    }

    @DeleteMapping("/{id}/access/{accessId}")
    public ResponseEntity<Void> revokeAccess(HttpServletRequest request,
                                             @PathVariable String id,
                                             @PathVariable String accessId) {
        requirePermission(request);
        String tenantId = requireTenant();
        load(id, tenantId);

        Map<String, Object> row = accessRepository.findById(accessId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Access grant not found"));
        // A grant id from another mailbox must not be revocable through this mailbox's path.
        if (!id.equals(row.get("mailbox_id"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Access grant not found");
        }
        accessRepository.revoke(accessId, tenantId);
        log.info("Mailbox {} access grant {} revoked in tenant {}", id, accessId, tenantId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- Escalation contacts

    /**
     * Who gets told when a thread in this mailbox breaches its SLA.
     *
     * <p>A table rather than columns on the mailbox because escalation is a chain: level 1 goes to
     * the team, level 3 to whoever owns the outcome. Columns would cap it at whatever we guessed.
     */
    @GetMapping("/{id}/escalation-contacts")
    public ResponseEntity<Map<String, Object>> listEscalationContacts(HttpServletRequest request,
                                                                      @PathVariable String id) {
        requirePermission(request);
        String tenantId = requireTenant();
        load(id, tenantId);
        List<Map<String, Object>> records = escalationRepository.listContacts(tenantId, id)
                .stream().map(this::projectContact).toList();
        return ResponseEntity.ok(
                JsonApiResponseBuilder.collection("mailbox-escalation-contacts", records));
    }

    @PostMapping("/{id}/escalation-contacts")
    public ResponseEntity<Map<String, Object>> addEscalationContact(HttpServletRequest request,
                                                                    @PathVariable String id,
                                                                    @RequestBody Map<String, Object> body) {
        requirePermission(request);
        String tenantId = requireTenant();
        load(id, tenantId);

        Map<String, Object> attrs = attributes(body);
        String level = upper(attrs.get("level"));
        String userId = text(attrs.get("userId"));
        if (!ESCALATION_LEVELS.contains(level)) {
            throw badRequest("level must be one of " + ESCALATION_LEVELS);
        }
        if (userId == null) {
            throw badRequest("userId is required");
        }
        List<String> channels = channels(attrs.get("channels"));
        if (channels.isEmpty()) {
            throw badRequest("at least one channel is required");
        }
        for (String channel : channels) {
            if (!ESCALATION_CHANNELS.contains(channel)) {
                throw badRequest("channel must be one of " + ESCALATION_CHANNELS);
            }
        }

        String resolvedUser = accessGuard.resolveUserId(tenantId, userId);
        if (resolvedUser == null) {
            throw badRequest("No user in this tenant matches '" + userId + "'");
        }
        String contactId = escalationRepository.addContact(tenantId, id, level, resolvedUser,
                toJson(channels), actor(request));
        log.info("Mailbox {} escalation contact added: {} at {} in tenant {}",
                id, userId, level, tenantId);

        Map<String, Object> row = escalationRepository.findContact(contactId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                JsonApiResponseBuilder.single("mailbox-escalation-contacts", contactId, projectContact(row)));
    }

    @DeleteMapping("/{id}/escalation-contacts/{contactId}")
    public ResponseEntity<Void> removeEscalationContact(HttpServletRequest request,
                                                        @PathVariable String id,
                                                        @PathVariable String contactId) {
        requirePermission(request);
        String tenantId = requireTenant();
        load(id, tenantId);

        Map<String, Object> row = escalationRepository.findContact(contactId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        // A contact id from another mailbox must not be removable through this mailbox's path.
        if (!id.equals(row.get("mailbox_id"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        escalationRepository.removeContact(contactId, tenantId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- Auto-reply reporting

    /**
     * What auto-reply has been doing.
     *
     * <p>Reports {@code shadowMode} first, because every other number means something different
     * depending on it: in shadow mode a SHADOW count is "replies we would have sent", and live it
     * is nothing at all.
     *
     * <p>Includes the follow-up rate deliberately. Auto-replying "thanks for writing!" to
     * everything scores perfect first-response compliance while helping nobody, and the share of
     * auto-replied threads where the customer wrote back is what exposes that.
     */
    @GetMapping("/{id}/auto-reply-report")
    public ResponseEntity<Map<String, Object>> autoReplyReport(HttpServletRequest request,
                                                               @PathVariable String id,
                                                               @RequestParam(defaultValue = "14") int days) {
        requirePermission(request);
        String tenantId = requireTenant();
        load(id, tenantId);
        int window = Math.max(1, Math.min(days, 90));

        Map<String, Object> followUp = decisionRepository.followUpRate(tenantId, id, window);
        long autoReplied = number(followUp.get("auto_replied"));
        long followedUp = number(followUp.get("followed_up"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shadowMode", autoReplySweep.isShadowMode());
        out.put("days", window);
        out.put("breakdown", decisionRepository.summarize(tenantId, id, window));
        out.put("autoReplied", autoReplied);
        out.put("followedUp", followedUp);
        out.put("followUpRate", autoReplied == 0 ? null : (double) followedUp / autoReplied);
        out.put("recent", decisionRepository.listRecent(tenantId, id, 50).stream()
                .map(this::projectDecision).toList());
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> projectContact(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mailboxId", row.get("mailbox_id"));
        out.put("level", row.get("level"));
        out.put("userId", row.get("user_id"));
        // Parsed rather than passed through: the driver hands back a PGobject wrapper, and
        // serialising that leaks {"type":"jsonb","value":"..."} into the API.
        out.put("channels", parseChannels(row.get("channels")));
        out.put("createdAt", row.get("created_at"));
        return out;
    }

    private Map<String, Object> projectDecision(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("threadId", row.get("thread_id"));
        out.put("subject", row.get("subject"));
        out.put("requesterEmail", row.get("requester_email"));
        out.put("outcome", row.get("outcome"));
        out.put("vetoReason", row.get("veto_reason"));
        out.put("matchedCategory", row.get("matched_category"));
        out.put("confidence", row.get("confidence"));
        out.put("ambiguous", row.get("ambiguous"));
        out.put("createdAt", row.get("created_at"));
        return out;
    }

    private List<String> channels(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull)
                    .map(o -> o.toString().toLowerCase(Locale.ROOT).trim())
                    .filter(s -> !s.isEmpty()).toList();
        }
        return List.of();
    }

    private String toJson(List<String> channels) {
        try {
            return objectMapper.writeValueAsString(channels);
        } catch (Exception e) {
            return "[\"email\"]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseChannels(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            return (List<String>) objectMapper.readValue(raw.toString(), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    // ---------------------------------------------------------------- Helpers

    private void requirePermission(HttpServletRequest request) {
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, PERMISSION + " permission required");
        }
    }

    private String requireTenant() {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        return tenantId;
    }

    private String actor(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        return userId != null && !userId.isBlank() ? userId : permissionResolver.getEmail(request);
    }

    private Map<String, Object> load(String id, String tenantId) {
        return mailboxRepository.findById(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mailbox not found"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attributes(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        // Accept both a JSON:API envelope and a plain object, matching the other admin controllers.
        if (body.get("data") instanceof Map<?, ?> data
                && ((Map<String, Object>) data).get("attributes") instanceof Map<?, ?> attrs) {
            return (Map<String, Object>) attrs;
        }
        return body;
    }

    /** Maps camelCase request keys onto column names, dropping anything not writable. */
    private Map<String, Object> normalize(Map<String, Object> attrs) {
        Map<String, Object> out = new LinkedHashMap<>();
        attrs.forEach((k, v) -> {
            String col = CAMEL_TO_COLUMN.get(k);
            if (col != null) {
                out.put(col, v);
            }
        });
        return out;
    }

    private void validate(Map<String, Object> attrs) {
        if (attrs.get("address") instanceof String a && !ADDRESS.matcher(a).matches()) {
            throw badRequest("address is not a valid email address");
        }
        if (attrs.get("inbound_provider") instanceof String p && !PROVIDERS.contains(p)) {
            throw badRequest("inboundProvider must be one of " + PROVIDERS);
        }
        positive(attrs, "sla_first_response_minutes", "slaFirstResponseMinutes");
        positive(attrs, "sla_resolution_minutes", "slaResolutionMinutes");
        positive(attrs, "max_auto_replies_per_thread", "maxAutoRepliesPerThread");
        positive(attrs, "max_auto_replies_per_day", "maxAutoRepliesPerDay");

        // The denylist is the auto-reply kill switch per category. It must be a list of plain
        // category names: a stray object or number in it would not fail the update — jsonb accepts
        // anything — it would silently stop matching the category it was meant to block.
        if (attrs.containsKey("auto_reply_blocked_categories")) {
            Object raw = attrs.get("auto_reply_blocked_categories");
            if (!(raw instanceof List<?> list)
                    || list.stream().anyMatch(v -> !(v instanceof String str) || str.isBlank())) {
                throw badRequest("autoReplyBlockedCategories must be a list of category names");
            }
        }

        if (attrs.get("sla_risk_threshold_pct") instanceof Number n
                && (n.intValue() < 1 || n.intValue() > 99)) {
            throw badRequest("slaRiskThresholdPct must be between 1 and 99");
        }
        if (attrs.get("auto_reply_min_confidence") instanceof Number n
                && (n.doubleValue() < 0.0 || n.doubleValue() > 1.0)) {
            throw badRequest("autoReplyMinConfidence must be between 0 and 1");
        }
    }

    private void positive(Map<String, Object> attrs, String col, String field) {
        if (attrs.get(col) instanceof Number n && n.intValue() <= 0) {
            throw badRequest(field + " must be greater than zero");
        }
    }

    private void requireText(Map<String, Object> attrs, String col) {
        if (!(attrs.get(col) instanceof String s) || s.isBlank()) {
            throw badRequest(col + " is required");
        }
    }

    /**
     * Shapes a row for the API.
     *
     * <p>Never emits {@code inbound_secret_credential_id} or its previous-slot twin. They are
     * pointers rather than secrets, but publishing them tells a reader exactly which vault row
     * to go after, and nothing in the UI needs them — the hint and the rotation timestamp are
     * what an admin actually reads.
     */
    /** jsonb columns that hold a list of strings and must be parsed, not passed through. */
    private static final java.util.Set<String> JSON_LIST_COLUMNS =
            java.util.Set.of("inbound_allowed_cidrs", "auto_reply_blocked_categories");

    private Map<String, Object> project(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        COLUMN_TO_CAMEL.forEach((col, camel) -> {
            if (row.containsKey(col)) {
                // The driver hands jsonb back as a PGobject; serialised as-is it leaks
                // {"type":"jsonb","value":"[...]"} into the API instead of the list.
                out.put(camel, JSON_LIST_COLUMNS.contains(col)
                        ? parseChannels(row.get(col)) : row.get(col));
            }
        });
        return out;
    }

    private Map<String, Object> projectAccess(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mailboxId", row.get("mailbox_id"));
        out.put("principalType", row.get("principal_type"));
        out.put("principalId", row.get("principal_id"));
        out.put("role", row.get("role"));
        out.put("createdAt", row.get("created_at"));
        out.put("createdBy", row.get("created_by"));
        return out;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String text(Object o) {
        return o instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String upper(Object o) {
        String s = text(o);
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_PAGE));
    }

    /**
     * The API's camelCase surface, and the columns behind it.
     *
     * <p>An allowlist in both directions on purpose. Inbound it means a request cannot reach a
     * column that is not listed — notably {@code webhook_key} and the credential references,
     * which are minted server-side and would otherwise be settable by a caller. Outbound it
     * means a column added to the table later is not published by accident.
     */
    private static final Map<String, String> COLUMN_TO_CAMEL = new LinkedHashMap<>();
    private static final Map<String, String> CAMEL_TO_COLUMN = new LinkedHashMap<>();

    static {
        // Read-only in the API: minted or maintained server-side.
        COLUMN_TO_CAMEL.put("id", "id");
        COLUMN_TO_CAMEL.put("webhook_key", "webhookKey");
        COLUMN_TO_CAMEL.put("inbound_secret_hint", "inboundSecretHint");
        COLUMN_TO_CAMEL.put("inbound_secret_rotated_at", "inboundSecretRotatedAt");
        COLUMN_TO_CAMEL.put("inbound_prev_secret_expires_at", "inboundPrevSecretExpiresAt");
        COLUMN_TO_CAMEL.put("created_at", "createdAt");
        COLUMN_TO_CAMEL.put("updated_at", "updatedAt");

        // Writable.
        Map<String, String> writable = new LinkedHashMap<>();
        writable.put("name", "name");
        writable.put("description", "description");
        writable.put("address", "address");
        writable.put("reply_from_address", "replyFromAddress");
        writable.put("reply_from_name", "replyFromName");
        writable.put("verp_domain", "verpDomain");
        writable.put("inbound_provider", "inboundProvider");
        writable.put("provider_topic_arn", "providerTopicArn");
        writable.put("inbound_allowed_cidrs", "inboundAllowedCidrs");
        writable.put("max_message_bytes", "maxMessageBytes");
        writable.put("max_attachments", "maxAttachments");
        writable.put("max_attachment_bytes", "maxAttachmentBytes");
        writable.put("subject_threading_days", "subjectThreadingDays");
        writable.put("sla_first_response_minutes", "slaFirstResponseMinutes");
        writable.put("sla_resolution_minutes", "slaResolutionMinutes");
        writable.put("sla_risk_threshold_pct", "slaRiskThresholdPct");
        writable.put("business_timezone", "businessTimezone");
        writable.put("escalation_user_id", "escalationUserId");
        writable.put("escalation_group_id", "escalationGroupId");
        writable.put("auto_reply_enabled", "autoReplyEnabled");
        writable.put("auto_reply_min_confidence", "autoReplyMinConfidence");
        writable.put("max_auto_replies_per_thread", "maxAutoRepliesPerThread");
        writable.put("max_auto_replies_per_day", "maxAutoRepliesPerDay");
        writable.put("auto_reply_blocked_categories", "autoReplyBlockedCategories");
        writable.put("ai_draft_enabled", "aiDraftEnabled");
        writable.put("require_verified_sender_for_account_data", "requireVerifiedSenderForAccountData");
        writable.put("default_assignee_id", "defaultAssigneeId");
        writable.put("active", "active");

        COLUMN_TO_CAMEL.putAll(writable);
        writable.forEach((col, camel) -> CAMEL_TO_COLUMN.put(camel, col));
    }
}
