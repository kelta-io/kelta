package io.kelta.worker.service;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ReferenceConfig;
import io.kelta.runtime.model.StorageConfig;
import io.kelta.runtime.query.Pagination;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.query.QueryRequest;
import io.kelta.runtime.query.QueryResult;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.RecordMergeService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("RecordMergeService")
class RecordMergeServiceTest {

    private QueryEngine queryEngine;
    private CollectionRegistry collectionRegistry;
    private RecordMergeService service;

    private CollectionDefinition contactsDef;
    private CollectionDefinition ordersDef;

    @BeforeEach
    void setUp() {
        queryEngine = mock(QueryEngine.class);
        collectionRegistry = mock(CollectionRegistry.class);
        service = new RecordMergeService(queryEngine, collectionRegistry);

        contactsDef = CollectionDefinition.builder()
                .name("contacts")
                .storageConfig(StorageConfig.physicalTable("contacts"))
                .addField(FieldDefinition.string("email"))
                .build();

        // orders.contact is a MASTER_DETAIL lookup targeting contacts — the inbound FK to re-parent.
        FieldDefinition contactLookup = new FieldDefinition(
                "contact", FieldType.MASTER_DETAIL, false, false, false,
                null, null, null, ReferenceConfig.masterDetail("contacts", "Contact"), null);
        ordersDef = CollectionDefinition.builder()
                .name("orders")
                .storageConfig(StorageConfig.physicalTable("orders"))
                .addField(FieldDefinition.string("total"))
                .addField(contactLookup)
                .build();
    }

    /** Wires the registry + existence checks so a contacts merge sees orders as an inbound FK. */
    private void stubTopology() {
        when(collectionRegistry.get("contacts")).thenReturn(contactsDef);
        when(collectionRegistry.get("orders")).thenReturn(ordersDef);
        when(collectionRegistry.getAllForCurrentTenant()).thenReturn(List.of(contactsDef, ordersDef));
        when(queryEngine.getById(any(), anyString()))
                .thenAnswer(inv -> Optional.of(Map.of("id", inv.getArgument(1))));
        when(queryEngine.delete(any(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("re-parents inbound FKs off duplicates, then deletes them")
    void reparentsAndDeletes() {
        stubTopology();
        // Each duplicate has one child order; the second executeQuery per duplicate drains the filter.
        when(queryEngine.executeQuery(eq(ordersDef), any(QueryRequest.class)))
                .thenAnswer(inv -> {
                    QueryRequest req = inv.getArgument(1);
                    Object dupId = req.filters().get(0).value();
                    return QueryResult.of(
                            List.of(Map.of("id", "order-of-" + dupId, "contact", dupId)),
                            1, new Pagination(1, 1000));
                });

        Result result = service.merge("contacts", "master", List.of("dup1", "dup2"), Map.of());

        // Both children re-parented onto the master.
        ArgumentCaptor<Map<String, Object>> attrs = ArgumentCaptor.forClass(Map.class);
        verify(queryEngine).update(eq(ordersDef), eq("order-of-dup1"), attrs.capture());
        verify(queryEngine).update(eq(ordersDef), eq("order-of-dup2"), any());
        assertThat(attrs.getValue()).containsEntry("contact", "master");

        // Duplicates deleted; master never deleted.
        verify(queryEngine).delete(contactsDef, "dup1");
        verify(queryEngine).delete(contactsDef, "dup2");
        verify(queryEngine, never()).delete(any(), eq("master"));

        assertThat(result.masterId()).isEqualTo("master");
        assertThat(result.deletedIds()).containsExactlyInAnyOrder("dup1", "dup2");
        assertThat(result.reparentedRecords()).isEqualTo(2);
        assertThat(result.reparented()).singleElement().satisfies(r -> {
            assertThat(r.collection()).isEqualTo("orders");
            assertThat(r.field()).isEqualTo("contact");
            assertThat(r.count()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("applies field overrides to the master via the normal update path")
    void appliesOverrides() {
        stubTopology();
        when(queryEngine.executeQuery(eq(ordersDef), any(QueryRequest.class)))
                .thenReturn(QueryResult.of(List.of(), 0, new Pagination(1, 1000)));

        service.merge("contacts", "master", List.of("dup1"), Map.of("email", "kept@x.com"));

        verify(queryEngine).update(eq(contactsDef), eq("master"), eq(Map.of("email", "kept@x.com")));
    }

    @Test
    @DisplayName("skips the master update when there are no overrides")
    void noOverrideNoMasterUpdate() {
        stubTopology();
        when(queryEngine.executeQuery(eq(ordersDef), any(QueryRequest.class)))
                .thenReturn(QueryResult.of(List.of(), 0, new Pagination(1, 1000)));

        service.merge("contacts", "master", List.of("dup1"), Map.of());

        verify(queryEngine, never()).update(eq(contactsDef), eq("master"), any());
    }

    @Test
    @DisplayName("skips an immutable inbound FK instead of counting a write it cannot make")
    void skipsImmutableInboundReference() {
        // Same topology, except orders.contact is immutable on the orders collection.
        CollectionDefinition immutableOrdersDef = CollectionDefinition.builder()
                .name("orders")
                .storageConfig(StorageConfig.physicalTable("orders"))
                .addField(FieldDefinition.string("total"))
                .addField(new FieldDefinition(
                        "contact", FieldType.MASTER_DETAIL, false, false, false,
                        null, null, null, ReferenceConfig.masterDetail("contacts", "Contact"), null))
                .addImmutableField("contact")
                .build();

        when(collectionRegistry.get("contacts")).thenReturn(contactsDef);
        when(collectionRegistry.get("orders")).thenReturn(immutableOrdersDef);
        when(collectionRegistry.getAllForCurrentTenant()).thenReturn(List.of(contactsDef, immutableOrdersDef));
        when(queryEngine.getById(any(), anyString()))
                .thenAnswer(inv -> Optional.of(Map.of("id", inv.getArgument(1))));
        when(queryEngine.delete(any(), anyString())).thenReturn(true);

        Result result = service.merge("contacts", "master", List.of("dup1"), Map.of());

        // No scan, no update attempt — the write would be rejected as immutable.
        verify(queryEngine, never()).executeQuery(eq(immutableOrdersDef), any(QueryRequest.class));
        verify(queryEngine, never()).update(eq(immutableOrdersDef), anyString(), any());

        assertThat(result.reparentedRecords()).isZero();
        assertThat(result.reparented()).isEmpty();
        assertThat(result.skippedImmutable()).singleElement().satisfies(s -> {
            assertThat(s.collection()).isEqualTo("orders");
            assertThat(s.field()).isEqualTo("contact");
        });
    }

    @Test
    @DisplayName("rejects blank master, empty duplicates, and the master listed as a duplicate")
    void inputValidation() {
        assertThatThrownBy(() -> service.merge("contacts", " ", List.of("d1"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge("contacts", "master", List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge("contacts", "master", List.of("master"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects unknown collection and missing records before any write")
    void existenceValidation() {
        when(collectionRegistry.get("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.merge("missing", "master", List.of("d1"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        when(collectionRegistry.get("contacts")).thenReturn(contactsDef);
        when(queryEngine.getById(any(), eq("master"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.merge("contacts", "master", List.of("d1"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(queryEngine, never()).delete(any(), anyString());
    }
}
