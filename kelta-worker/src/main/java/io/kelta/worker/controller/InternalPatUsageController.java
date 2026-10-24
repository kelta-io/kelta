package io.kelta.worker.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Records that a personal access token was used. Called fire-and-forget by the gateway's
 * {@code PatAuthenticationFilter} after it authenticates a request with a PAT, at most once per
 * token per window per gateway replica (an in-memory Caffeine throttle).
 *
 * <p>The window arrives as the {@code windowSeconds} request parameter, which the gateway fills
 * from {@code kelta.gateway.pat-last-used.window-seconds}; it defaults to
 * {@value #DEFAULT_WINDOW_SECONDS}. The UPDATE itself only writes when {@code last_used_at} is
 * older than that window, so the at-most-once-per-window guarantee also holds across gateway
 * replicas — the gateway throttle only cuts traffic.
 *
 * <p>Runs with no tenant bound (the gateway sends no tenant header), so RLS's
 * {@code admin_bypass} policy applies, as for {@code GET /api/me/tokens/validate/{hash}}.
 * Always answers 204: the caller neither waits for nor acts on the outcome.
 */
@RestController
@RequestMapping("/internal/tokens")
public class InternalPatUsageController {

    static final long DEFAULT_WINDOW_SECONDS = 300;

    private static final String TOUCH_SQL =
            "UPDATE user_api_token SET last_used_at = now() "
                    + "WHERE token_hash = ? AND revoked = false "
                    + "AND (last_used_at IS NULL OR last_used_at < now() - make_interval(secs => ?))";

    private final JdbcTemplate jdbcTemplate;

    public InternalPatUsageController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/{tokenHash}/last-used")
    public ResponseEntity<Void> recordLastUsed(
            @PathVariable String tokenHash,
            @RequestParam(defaultValue = "" + DEFAULT_WINDOW_SECONDS) long windowSeconds) {
        touchLastUsed(tokenHash, windowSeconds);
        return ResponseEntity.noContent().build();
    }

    /** Returns the number of rows updated: 1 on the first use in a window, otherwise 0. */
    int touchLastUsed(String tokenHash, long windowSeconds) {
        return jdbcTemplate.update(TOUCH_SQL, tokenHash, (double) Math.max(0, windowSeconds));
    }
}
