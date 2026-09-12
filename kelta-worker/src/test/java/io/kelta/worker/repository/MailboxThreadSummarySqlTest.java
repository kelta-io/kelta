package io.kelta.worker.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The console header's SLA counts must exclude closed threads.
 *
 * <p>Without that, archiving a breached thread leaves it in the red badge forever: after eleven
 * junk threads were archived the summary still read {@code breached: 11} over an inbox with one
 * open conversation. A count that cannot go down is not a count anyone will trust.
 */
@DisplayName("MailboxThreadRepository summary SQL")
class MailboxThreadSummarySqlTest {

    @Test
    @DisplayName("at_risk and breached are scoped to non-closed threads")
    void slaCountsExcludeClosedThreads() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of());
        new MailboxThreadRepository(jdbc).summary("t1", List.of("mb1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForMap(sql.capture(), any(Object[].class));

        String breached = sql.getValue().substring(sql.getValue().indexOf("AS at_risk"));
        assertThat(breached)
                .as("the breached filter must carry a status exclusion")
                .contains("status NOT IN")
                .contains("sla_first_response_state = 'BREACHED'");
        String atRisk = sql.getValue().substring(
                sql.getValue().indexOf("AS unassigned"), sql.getValue().indexOf("AS at_risk"));
        assertThat(atRisk).contains("status NOT IN").contains("'AT_RISK'");
    }
}
