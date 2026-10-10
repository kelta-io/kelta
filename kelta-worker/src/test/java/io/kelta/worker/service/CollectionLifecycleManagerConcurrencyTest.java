package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.OwnerScope;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.storage.StorageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CollectionLifecycleManager — concurrent refreshes of one collection")
class CollectionLifecycleManagerConcurrencyTest {

    private static Map<String, Object> row(String ownerScope) {
        Map<String, Object> row = new HashMap<>();
        row.put("name", "items");
        row.put("active", true);
        row.put("owner_field", "PORTAL".equals(ownerScope) ? "createdBy" : null);
        row.put("owner_scope", ownerScope);
        return row;
    }

    @Test
    @DisplayName("a refresh that read the row before a change cannot register after the refresh that read it after")
    void laterRefreshRegistersLast() throws Exception {
        CollectionRegistry registry = mock(CollectionRegistry.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CollectionLifecycleManager manager = new CollectionLifecycleManager(
                registry, mock(StorageAdapter.class), jdbc, new ObjectMapper());

        CountDownLatch staleReadStarted = new CountDownLatch(1);
        CountDownLatch releaseStaleRead = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(jdbc.queryForList(argThat((String sql) -> sql != null && sql.contains("FROM collection WHERE id = ?")),
                any(Object[].class))).thenAnswer(inv -> {
            switch (reads.incrementAndGet()) {
                case 1 -> { return List.of(row("NONE")); }
                case 2 -> {
                    // The NATS consumer's read lands before the ownership PATCH commits.
                    staleReadStarted.countDown();
                    assertThat(releaseStaleRead.await(10, TimeUnit.SECONDS)).isTrue();
                    return List.of(row("NONE"));
                }
                default -> { return List.of(row("PORTAL")); }
            }
        });

        manager.initializeCollection("c1");

        Thread consumer = Thread.ofPlatform().start(() -> manager.refreshCollection("c1"));
        assertThat(staleReadStarted.await(10, TimeUnit.SECONDS)).isTrue();
        Thread readAfterWrite = Thread.ofPlatform().start(() -> manager.refreshOrInitializeLocally("c1"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (readAfterWrite.isAlive() && readAfterWrite.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        releaseStaleRead.countDown();
        consumer.join(10_000);
        readAfterWrite.join(10_000);

        ArgumentCaptor<CollectionDefinition> registered = ArgumentCaptor.forClass(CollectionDefinition.class);
        verify(registry, atLeastOnce()).register(registered.capture());
        List<CollectionDefinition> all = registered.getAllValues();
        assertThat(all).hasSize(3);
        assertThat(all.getLast().ownerScope()).isEqualTo(OwnerScope.PORTAL);
    }
}
