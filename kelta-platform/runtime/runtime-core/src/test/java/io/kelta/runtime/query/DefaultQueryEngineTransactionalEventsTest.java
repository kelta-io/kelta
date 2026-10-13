package io.kelta.runtime.query;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import io.kelta.runtime.model.*;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.ValidationEngine;
import io.kelta.runtime.validation.ValidationResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Record change events from a transactional write are published after commit and never on
 * rollback; without a transaction they are published synchronously.
 */
class DefaultQueryEngineTransactionalEventsTest {

    private StorageAdapter storageAdapter;
    private RecordEventPublisher publisher;
    private DefaultQueryEngine engine;
    private CollectionDefinition collection;

    @BeforeEach
    void setUp() {
        storageAdapter = mock(StorageAdapter.class);
        ValidationEngine validationEngine = mock(ValidationEngine.class);
        publisher = mock(RecordEventPublisher.class);
        engine = new DefaultQueryEngine(storageAdapter, validationEngine, null, null, null, null, null,
                publisher);

        collection = new CollectionDefinitionBuilder()
                .name("products")
                .displayName("Products")
                .addField(new FieldDefinitionBuilder().name("name").type(FieldType.STRING).nullable(true).build())
                .build();

        when(validationEngine.validate(eq(collection), any(), any())).thenReturn(ValidationResult.success());
        when(validationEngine.validate(eq(collection), any(), any(), any())).thenReturn(ValidationResult.success());
        when(storageAdapter.create(eq(collection), any())).thenAnswer(inv -> inv.getArgument(1));
        when(storageAdapter.getById(eq(collection), any())).thenAnswer(inv ->
                Optional.of(new HashMap<>(Map.of("id", inv.getArgument(1), "name", "Old"))));
        when(storageAdapter.update(eq(collection), any(), any())).thenAnswer(inv -> {
            Map<String, Object> result = new HashMap<>(Map.of("id", inv.getArgument(1)));
            result.putAll(inv.getArgument(2));
            return Optional.of(result);
        });
        when(storageAdapter.delete(eq(collection), any())).thenReturn(true);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void writeCreateUpdateDelete() {
        engine.create(collection, new HashMap<>(Map.of("name", "New")));
        engine.update(collection, "rec-2", new HashMap<>(Map.of("name", "Changed")));
        engine.delete(collection, "rec-3");
    }

    private static void commit(List<TransactionSynchronization> syncs) {
        syncs.forEach(s -> s.beforeCommit(false));
        syncs.forEach(TransactionSynchronization::afterCommit);
        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    @Test
    @DisplayName("Inside a transaction events wait for afterCommit, then publish once each in write order")
    void publishesAfterCommitInWriteOrder() {
        TransactionSynchronizationManager.initSynchronization();

        writeCreateUpdateDelete();

        verifyNoInteractions(publisher);
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(3, syncs.size());

        commit(syncs);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<PlatformEvent<RecordChangedPayload>> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(publisher, times(3)).publish(captor.capture());
        List<PlatformEvent<RecordChangedPayload>> events = captor.getAllValues();
        assertEquals(ChangeType.CREATED, events.get(0).getPayload().getChangeType());
        assertEquals(ChangeType.UPDATED, events.get(1).getPayload().getChangeType());
        assertEquals("rec-2", events.get(1).getPayload().getRecordId());
        assertEquals(ChangeType.DELETED, events.get(2).getPayload().getChangeType());
        assertEquals("rec-3", events.get(2).getPayload().getRecordId());
    }

    @Test
    @DisplayName("On rollback no event is published")
    void rollbackPublishesNothing() {
        TransactionSynchronizationManager.initSynchronization();

        writeCreateUpdateDelete();

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(3, syncs.size());
        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("Without a transaction each write publishes synchronously")
    void publishesImmediatelyWithoutTransaction() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        engine.create(collection, new HashMap<>(Map.of("name", "New")));
        verify(publisher, times(1)).publish(any());

        engine.update(collection, "rec-2", new HashMap<>(Map.of("name", "Changed")));
        verify(publisher, times(2)).publish(any());

        engine.delete(collection, "rec-3");
        verify(publisher, times(3)).publish(any());
    }

    @Test
    @DisplayName("A publish failure in afterCommit is swallowed and later events still publish")
    void afterCommitFailureIsSwallowed() {
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new RuntimeException("NATS is down"))
                .doNothing()
                .when(publisher).publish(any());

        writeCreateUpdateDelete();
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();

        assertDoesNotThrow(() -> commit(syncs));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<PlatformEvent<RecordChangedPayload>> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(publisher, times(3)).publish(captor.capture());
        List<ChangeType> types = captor.getAllValues().stream().map(e -> e.getPayload().getChangeType()).toList();
        assertEquals(List.of(ChangeType.CREATED, ChangeType.UPDATED, ChangeType.DELETED), types);
    }
}
