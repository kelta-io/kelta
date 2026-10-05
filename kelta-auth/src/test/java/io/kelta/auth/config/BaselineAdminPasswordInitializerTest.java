package io.kelta.auth.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.kelta.auth.config.BaselineAdminPasswordInitializer.ADMIN_EMAIL;
import static io.kelta.auth.config.BaselineAdminPasswordInitializer.BASELINE_HASH;
import static io.kelta.auth.config.BaselineAdminPasswordInitializer.PLATFORM_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BaselineAdminPasswordInitializer")
class BaselineAdminPasswordInitializerTest {

    private static final String USER_ID = "63d8a151-211d-43ca-9c10-7c3ffe79f9de";
    private static final String NEW_HASH = "{bcrypt}$2a$10$new";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthProperties properties = new AuthProperties();
    private final BaselineAdminPasswordInitializer initializer =
            new BaselineAdminPasswordInitializer(jdbc, encoder, properties);

    private LogCapture logs;

    @BeforeEach
    void captureLogs() {
        logs = LogCapture.onCurrentThread(BaselineAdminPasswordInitializer.class);
        when(encoder.encode(anyString())).thenReturn(NEW_HASH);
    }

    @AfterEach
    void detach() {
        logs.close();
    }

    @SuppressWarnings("unchecked")
    private void storedHash(String hash) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(PLATFORM_TENANT_ID), eq(ADMIN_EMAIL)))
                .thenAnswer(inv -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("id")).thenReturn(USER_ID);
                    when(rs.getString("username")).thenReturn("admin");
                    when(rs.getString("password_hash")).thenReturn(hash);
                    return List.of(((RowMapper<Object>) inv.getArgument(1)).mapRow(rs, 0));
                });
    }

    private void updateChanges(int rows) {
        when(jdbc.update(startsWith("UPDATE user_credential"), eq(NEW_HASH), eq(USER_ID), eq(BASELINE_HASH)))
                .thenReturn(rows);
    }

    private List<ILoggingEvent> events() {
        return logs.events();
    }

    private List<ILoggingEvent> warnings() {
        return events().stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("0-row update (another replica won the race): no banner, no WARN, no revocation")
    void losingReplicaPrintsNothing() {
        storedHash(BASELINE_HASH);
        updateChanges(0);

        initializer.run(null);

        assertThat(events()).isEmpty();
        verify(jdbc, never()).update(startsWith("DELETE FROM oauth2_authorization"), any(), any());
    }

    @Test
    @DisplayName("the replacement is one UPDATE conditional on the baseline hash")
    void updateIsConditionalOnBaselineHash() {
        storedHash(BASELINE_HASH);
        updateChanges(1);

        initializer.run(null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(NEW_HASH), eq(USER_ID), eq(BASELINE_HASH));
        assertThat(sql.getValue())
                .contains("force_change_on_login = true")
                .contains("password_changed_at = now()")
                .endsWith("WHERE user_id = ? AND password_hash = ?");
        verify(jdbc).update(startsWith("DELETE FROM oauth2_authorization"), eq(ADMIN_EMAIL), eq("admin"));
    }

    @Test
    @DisplayName("a generated password is printed once, in a single WARN banner")
    void generatedPasswordBanner() {
        storedHash(BASELINE_HASH);
        updateChanges(1);

        initializer.run(null);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        verify(encoder).encode(raw.capture());
        assertThat(raw.getValue()).hasSize(BaselineAdminPasswordInitializer.GENERATED_LENGTH);
        assertThat(warnings()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("Kelta platform admin initial password")
                .contains(ADMIN_EMAIL)
                .contains("Password:  " + raw.getValue())
                .contains("KELTA_BOOTSTRAP_ADMIN_PASSWORD"));
    }

    @Test
    @DisplayName("a configured password is applied and never logged")
    void configuredPasswordIsNotLogged() {
        properties.setBootstrapAdminPassword("configured-secret-value");
        storedHash(BASELINE_HASH);
        updateChanges(1);

        initializer.run(null);

        verify(encoder).encode("configured-secret-value");
        assertThat(warnings()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains(ADMIN_EMAIL)
                .contains("KELTA_BOOTSTRAP_ADMIN_PASSWORD")
                .doesNotContain("Password:"));
        assertThat(events()).noneMatch(e -> e.getFormattedMessage().contains("configured-secret-value"));
    }

    @Test
    @DisplayName("an admin hash other than the baseline is left alone, silently")
    void changedHashIsUntouched() {
        storedHash("{bcrypt}$2a$10$somethingTheAdminChose");

        initializer.run(null);

        verify(jdbc, never()).update(startsWith("UPDATE"), any(), any(), any());
        verify(encoder, never()).encode(anyString());
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("no platform admin row: nothing to do")
    @SuppressWarnings("unchecked")
    void missingAdminIsIgnored() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(PLATFORM_TENANT_ID), eq(ADMIN_EMAIL)))
                .thenReturn(List.of());

        initializer.run(null);

        verify(jdbc, never()).update(startsWith("UPDATE"), any(), any(), any());
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("generated passwords are 24 chars and do not repeat")
    void generatedPasswords() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String p = BaselineAdminPasswordInitializer.generatePassword();
            assertThat(p).hasSize(24).matches("[A-Za-z0-9]+");
            seen.add(p);
        }
        assertThat(seen).hasSize(50);
    }
}
