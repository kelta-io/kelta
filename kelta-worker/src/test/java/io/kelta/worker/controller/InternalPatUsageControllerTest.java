package io.kelta.worker.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InternalPatUsageController")
class InternalPatUsageControllerTest {

    private JdbcTemplate jdbcTemplate;
    private InternalPatUsageController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new InternalPatUsageController(jdbcTemplate);
    }

    @Test
    @DisplayName("runs the conditional UPDATE with the token hash and window")
    void runsConditionalUpdate() {
        controller.recordLastUsed("hash-1", 120);

        verify(jdbcTemplate).update(
                contains("UPDATE user_api_token SET last_used_at = now() WHERE token_hash = ? AND revoked = false "
                        + "AND (last_used_at IS NULL OR last_used_at < now() - make_interval(secs => ?))"),
                eq("hash-1"), eq(120.0));
    }

    @Test
    @DisplayName("answers 204 whether or not a row changed")
    void alwaysNoContent() {
        when(jdbcTemplate.update(anyString(), eq("fresh"), eq(300.0))).thenReturn(1);
        when(jdbcTemplate.update(anyString(), eq("throttled"), eq(300.0))).thenReturn(0);

        ResponseEntity<Void> updated = controller.recordLastUsed("fresh", 300);
        ResponseEntity<Void> unchanged = controller.recordLastUsed("throttled", 300);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("a negative window is clamped to zero")
    void negativeWindowClamped() {
        controller.recordLastUsed("hash-1", -5);

        verify(jdbcTemplate).update(anyString(), eq("hash-1"), eq(0.0));
    }
}
