package io.kelta.worker.module;

import io.kelta.runtime.flow.AwaitedTaskOutcomes;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ModuleTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code module_async_task} (V223): the tasks of long-running external-module actions
 * ({@code specs/module-platform/9-external-modules.md} §3.6.2), and the flow engine's
 * {@link AwaitedTaskOutcomes} for them.
 *
 * <p><b>The token.</b> {@code kmt_} plus 32 random bytes from {@link SecureRandom}, base64url
 * without padding (256 bits). Only its SHA-256 is stored ({@code token_hash}, unique). A row is
 * bound to its tenant, install, flow execution, step and attempt, and expires at the action's
 * {@code maxDurationSeconds} capped by the platform's
 * {@code kelta.modules.external.async-action-max-duration-seconds}.
 *
 * <p><b>Single use.</b> Every state change is one conditional {@code UPDATE ... WHERE status =
 * 'PENDING'}: a completion moves the row to {@code COMPLETED} only while it is unexpired and
 * belongs to the calling install and tenant; the resuming step moves it to {@code EXPIRED} when no
 * completion came. Whichever runs first wins, so a token completes at most once and never after
 * its step gave up, and cancelling the execution expires its pending tasks ({@link #abandon}).
 * Every statement filters on the tenant as well as RLS doing so.
 *
 * <p><b>Resume key.</b> {@code module-task:<id>}, the {@code flow_pending_resume.resume_event}
 * of the parked step. {@link #delivered} and {@link #settle} answer only for keys of that shape.
 */
public class ModuleAsyncTaskStore implements AwaitedTaskOutcomes {

    static final String RESUME_KEY_PREFIX = "module-task:";
    static final String TOKEN_PREFIX = "kmt_";
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_EXPIRED = "EXPIRED";

    /** The default platform cap on a task's lifetime: one day. */
    public static final long DEFAULT_MAX_DURATION_SECONDS = 86_400;

    /** {@code kmt_} + base64url of 32 bytes (43 characters, no padding). */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^kmt_[A-Za-z0-9_-]{43}$");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String TIMEOUT_CODE = ModuleTimeoutException.class.getSimpleName();

    /**
     * A task as issued: the plaintext token exists only here and in the action request.
     *
     * @param id        the row id; the resume key is {@code module-task:<id>}
     * @param token     the plaintext token sent to the module
     * @param expiresAt when the parked step fails if the module has not completed it
     */
    public record Issued(String id, String token, Instant expiresAt) {

        public String resumeKey() {
            return RESUME_KEY_PREFIX + id;
        }

        /** Never prints the token. */
        @Override
        public String toString() {
            return "Issued{id=" + id + ", expiresAt=" + expiresAt + '}';
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Duration platformMax;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ModuleAsyncTaskStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, long platformMaxSeconds) {
        this(jdbcTemplate, objectMapper, platformMaxSeconds, Clock.systemUTC());
    }

    ModuleAsyncTaskStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, long platformMaxSeconds, Clock clock) {
        if (platformMaxSeconds < 1) {
            throw new IllegalArgumentException(
                    "kelta.modules.external.async-action-max-duration-seconds must be at least 1, was "
                            + platformMaxSeconds);
        }
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.platformMax = Duration.ofSeconds(platformMaxSeconds);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Issues a task for one call of a long-running action, before the call is made (a fast module
     * may complete it before its {@code 202} arrives). The caller binds the tenant.
     *
     * @param install            the called install
     * @param actionKey          the action's key, without the module prefix
     * @param context            the flow step's context: execution, step, attempt
     * @param maxDurationSeconds the manifest's {@code maxDurationSeconds}
     */
    public Issued issue(TenantModuleData install, String actionKey, ActionContext context, int maxDurationSeconds) {
        Duration lifetime = Duration.ofSeconds(maxDurationSeconds);
        if (lifetime.compareTo(platformMax) > 0) {
            lifetime = platformMax;
        }
        // Whole seconds: the module reads the same instant in the request and in a heartbeat, which
        // reads the stored column back (Postgres keeps microseconds, a Java clock nanoseconds).
        Instant expiresAt = clock.instant().plus(lifetime).truncatedTo(ChronoUnit.SECONDS);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO module_async_task "
                        + "(id, tenant_id, tenant_module_id, execution_id, step_name, attempt, action_key, token_hash, "
                        + "status, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, install.tenantId(), install.id(), context.executionLogId(), context.stepName(),
                Math.max(1, context.attempt()), actionKey, hash(token), STATUS_PENDING, Timestamp.from(expiresAt));
        return new Issued(id, token, expiresAt);
    }

    /** Drops a task the module never needed: it answered synchronously, or the call failed. */
    public void discard(String tenantId, Issued issued) {
        jdbcTemplate.update("DELETE FROM module_async_task WHERE id = ? AND tenant_id = ?", issued.id(), tenantId);
    }

    /**
     * Records the module's outcome, once: only a {@code PENDING}, unexpired task of this tenant
     * and install. An unknown, foreign, reused or expired token all answer empty alike.
     *
     * @param outcome {@code {success, output}} or {@code {success, errorMessage}}, already checked
     * @return the task's resume key, to resume the parked step by
     */
    public Optional<String> complete(String tenantId, String tenantModuleId, String token, Map<String, Object> outcome) {
        if (!wellFormed(token)) {
            return Optional.empty();
        }
        List<String> ids = jdbcTemplate.queryForList("UPDATE module_async_task "
                        + "SET status = 'COMPLETED', outcome = ?::jsonb, completed_at = now() "
                        + "WHERE token_hash = ? AND tenant_id = ? AND tenant_module_id = ? "
                        + "AND status = 'PENDING' AND expires_at > now() RETURNING id",
                String.class, objectMapper.writeValueAsString(outcome), hash(token), tenantId, tenantModuleId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(RESUME_KEY_PREFIX + ids.getFirst());
    }

    /**
     * Records that the module is still working on a {@code PENDING}, unexpired task of this tenant
     * and install. Empty for any other token, as {@link #complete}.
     *
     * @return the task's expiry
     */
    public Optional<Instant> heartbeat(String tenantId, String tenantModuleId, String token) {
        if (!wellFormed(token)) {
            return Optional.empty();
        }
        List<Timestamp> expiries = jdbcTemplate.queryForList("UPDATE module_async_task "
                        + "SET last_heartbeat_at = now() "
                        + "WHERE token_hash = ? AND tenant_id = ? AND tenant_module_id = ? "
                        + "AND status = 'PENDING' AND expires_at > now() RETURNING expires_at",
                Timestamp.class, hash(token), tenantId, tenantModuleId);
        return expiries.isEmpty() ? Optional.empty() : Optional.of(expiries.getFirst().toInstant());
    }

    @Override
    public boolean delivered(String tenantId, String resumeKey) {
        String id = idOf(resumeKey);
        if (id == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM module_async_task "
                + "WHERE id = ? AND tenant_id = ? AND status = 'COMPLETED'", Integer.class, id, tenantId);
        return count != null && count > 0;
    }

    @Override
    public Optional<TaskOutcome> settle(String tenantId, String resumeKey) {
        String id = idOf(resumeKey);
        if (id == null) {
            return Optional.empty();
        }
        // No outcome came: close the task so a late completion is refused, and time the step out.
        List<Map<String, Object>> expired = jdbcTemplate.queryForList("UPDATE module_async_task "
                + "SET status = 'EXPIRED', completed_at = now() "
                + "WHERE id = ? AND tenant_id = ? AND status = 'PENDING' "
                + "RETURNING attempt, action_key, created_at, expires_at", id, tenantId);
        if (!expired.isEmpty()) {
            return Optional.of(timedOut(expired.getFirst()));
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT status, outcome::text AS outcome, "
                + "attempt, action_key, created_at, expires_at FROM module_async_task "
                + "WHERE id = ? AND tenant_id = ?", id, tenantId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        if (!STATUS_COMPLETED.equals(row.get("status"))) {
            return Optional.of(timedOut(row));
        }
        return Optional.of(outcome(row));
    }

    /**
     * Expires the cancelled execution's pending tasks: their module can no longer complete them or
     * renew its access token with a heartbeat.
     */
    @Override
    public void abandon(String tenantId, String executionId) {
        jdbcTemplate.update("UPDATE module_async_task SET status = 'EXPIRED', completed_at = now() "
                + "WHERE execution_id = ? AND tenant_id = ? AND status = 'PENDING'", executionId, tenantId);
    }

    private TaskOutcome outcome(Map<String, Object> row) {
        int attempt = ((Number) row.get("attempt")).intValue();
        JsonNode outcome = objectMapper.readTree(String.valueOf(row.get("outcome")));
        if (outcome != null && outcome.path("success").asBoolean(false)) {
            JsonNode output = outcome.get("output");
            Map<String, Object> values = output == null || !output.isObject() ? Map.of()
                    : objectMapper.convertValue(output, ExternalModuleRegistry.MAP_TYPE);
            return TaskOutcome.succeeded(values, attempt);
        }
        String message = outcome == null || outcome.get("errorMessage") == null ? "the module reported a failure"
                : outcome.get("errorMessage").asString();
        // The flow error code of a synchronous success:false answer.
        return TaskOutcome.failed("ActionFailed", message, attempt);
    }

    private static TaskOutcome timedOut(Map<String, Object> row) {
        int attempt = ((Number) row.get("attempt")).intValue();
        Instant created = instant(row.get("created_at"));
        Instant expires = instant(row.get("expires_at"));
        String within = created == null || expires == null ? ""
                : " within " + Duration.between(created, expires).toSeconds() + " s";
        return TaskOutcome.failed(TIMEOUT_CODE, "The module action '" + row.get("action_key")
                + "' did not complete its task" + within, attempt);
    }

    /** The {@code {success, output}} / {@code {success, errorMessage}} stored as a task's outcome. */
    static Map<String, Object> outcomeOf(boolean success, Map<String, Object> output, String errorMessage) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("success", success);
        if (success) {
            outcome.put("output", output == null ? Map.of() : output);
        } else {
            outcome.put("errorMessage", errorMessage);
        }
        return outcome;
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return value instanceof Instant instant ? instant : null;
    }

    private static String idOf(String resumeKey) {
        if (resumeKey == null || !resumeKey.startsWith(RESUME_KEY_PREFIX)) {
            return null;
        }
        String id = resumeKey.substring(RESUME_KEY_PREFIX.length());
        return UUID_SHAPE.matcher(id).matches() ? id : null;
    }

    /** A token that could have been issued; anything else never reaches the database. */
    static boolean wellFormed(String token) {
        return token != null && TOKEN_SHAPE.matcher(token).matches();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
