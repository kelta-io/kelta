package io.kelta.worker.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * Guards the #1578 fix: concurrent writers raced on {@code MAX(version_number) + 1}. The real
 * race needs two Postgres sessions; these tests pin the two things the fix depends on — the
 * per-record advisory lock is taken before the insert, and the method runs in a transaction
 * (a transaction-scoped lock is worthless without one).
 */
class RecordVersionRepositoryTest {

    @Test
    @DisplayName("takes the per-record advisory lock before inserting the next version")
    void locksRecordBeforeInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RecordVersionRepository repo = new RecordVersionRepository(jdbc);

        repo.recordVersion("t1", "c1", "r1", "UPDATE", "{}", "[]", "u1", "USER");

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(RecordVersionRepository.LOCK_RECORD, Object.class, "t1", "c1", "r1");
        order.verify(jdbc).update(eq(RecordVersionRepository.INSERT_VERSION),
                anyString(), eq("t1"), eq("c1"), eq("r1"), eq("UPDATE"), eq("{}"), eq("[]"),
                eq("u1"), eq("USER"), eq("t1"), eq("c1"), eq("r1"));
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("lock is transaction-scoped and keyed on tenant, collection and record")
    void lockIsTransactionScopedPerRecord() {
        assertThat(RecordVersionRepository.LOCK_RECORD)
                .contains("pg_advisory_xact_lock")
                .contains("? || ':' || ? || ':' || ?");
    }

    @Test
    @DisplayName("recordVersion is @Transactional so the xact lock is held through the insert")
    void recordVersionIsTransactional() throws NoSuchMethodException {
        var method = RecordVersionRepository.class.getMethod("recordVersion",
                String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class);
        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
    }
}
