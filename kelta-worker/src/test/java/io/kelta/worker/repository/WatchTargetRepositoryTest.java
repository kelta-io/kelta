package io.kelta.worker.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * SQL shape of {@link WatchTargetRepository}; the real-Postgres behaviour is covered by
 * {@code SyntheticAvailabilityIntegrationTest}.
 */
@DisplayName("WatchTargetRepository")
class WatchTargetRepositoryTest {

    @Test
    @DisplayName("the poller work list excludes synthetic targets")
    @SuppressWarnings("unchecked")
    void findActiveBySourceExcludesSynthetic() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        new WatchTargetRepository(jdbc).findActiveBySource("t1", "probe");

        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("t1"), eq("probe"));
        assertThat(sql.getValue()).contains("AND active AND NOT synthetic");
    }

    @Test
    @DisplayName("every read selects the synthetic column")
    @SuppressWarnings("unchecked")
    void readsSyntheticColumn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        new WatchTargetRepository(jdbc).findById("t1", "target-1");

        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("t1"), eq("target-1"));
        assertThat(sql.getValue()).contains("active, synthetic");
    }

    @Test
    @DisplayName("the eight-argument constructor builds a real (non-synthetic) target")
    void legacyConstructorIsNotSynthetic() {
        assertThat(new WatchTarget("id", "t1", "s", "e", "n", null, null, true).synthetic())
                .isFalse();
        assertThat(List.of(new WatchTarget("id", "t1", "s", "e", "n", null, null, true, true)))
                .allMatch(WatchTarget::synthetic);
    }
}
