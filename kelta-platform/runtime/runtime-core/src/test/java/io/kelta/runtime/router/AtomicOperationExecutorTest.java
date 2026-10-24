package io.kelta.runtime.router;

import io.kelta.jsonapi.AtomicOperation;
import io.kelta.jsonapi.AtomicResult;
import io.kelta.runtime.context.GeoHeaders;
import io.kelta.runtime.context.GeoStamp;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AtomicOperationExecutor Tests")
class AtomicOperationExecutorTest {

    @Mock private QueryEngine queryEngine;
    @Mock private CollectionRegistry collectionRegistry;
    @Mock private CollectionDefinition contactsDef;

    private AtomicOperationExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AtomicOperationExecutor(queryEngine, collectionRegistry);
        lenient().when(collectionRegistry.get("contacts")).thenReturn(contactsDef);
    }

    @Nested
    @DisplayName("Add Operations")
    class AddOperations {
        @Test
        void shouldCreateRecordAndReturnResult() {
            var op = new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData("contacts", null, null,
                            Map.of("name", "John", "email", "john@example.com"), null));

            when(queryEngine.create(eq(contactsDef), anyMap()))
                    .thenReturn(Map.of("id", "server-1", "name", "John", "email", "john@example.com"));

            List<AtomicResult> results = executor.execute(List.of(op));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).data()).isNotNull();
            assertThat(results.get(0).data().type()).isEqualTo("contacts");
            assertThat(results.get(0).data().id()).isEqualTo("server-1");
        }

        @Test
        void shouldTrackLidToIdMapping() {
            var addOp = new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData("contacts", null, "temp-1",
                            Map.of("name", "John"), null));

            when(queryEngine.create(eq(contactsDef), anyMap()))
                    .thenReturn(Map.of("id", "server-1", "name", "John"));

            var updateOp = new AtomicOperation("update",
                    new AtomicOperation.ResourceRef("contacts", null, "temp-1"),
                    new AtomicOperation.ResourceData("contacts", null, null,
                            Map.of("name", "Updated John"), null));

            when(queryEngine.update(eq(contactsDef), eq("server-1"), anyMap()))
                    .thenReturn(Optional.of(Map.of("id", "server-1", "name", "Updated John")));

            List<AtomicResult> results = executor.execute(List.of(addOp, updateOp));

            assertThat(results).hasSize(2);
            assertThat(results.get(0).data().lid()).isEqualTo("temp-1");
            assertThat(results.get(1).data().id()).isEqualTo("server-1");
        }
    }

    @Nested
    @DisplayName("Update Operations")
    class UpdateOperations {
        @Test
        void shouldUpdateAndReturnResult() {
            var op = new AtomicOperation("update",
                    new AtomicOperation.ResourceRef("contacts", "123", null),
                    new AtomicOperation.ResourceData("contacts", null, null,
                            Map.of("name", "Updated"), null));

            when(queryEngine.update(eq(contactsDef), eq("123"), anyMap()))
                    .thenReturn(Optional.of(Map.of("id", "123", "name", "Updated")));

            List<AtomicResult> results = executor.execute(List.of(op));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).data().id()).isEqualTo("123");
        }

        @Test
        void shouldThrowWhenRecordNotFound() {
            var op = new AtomicOperation("update",
                    new AtomicOperation.ResourceRef("contacts", "missing", null),
                    new AtomicOperation.ResourceData("contacts", null, null,
                            Map.of("name", "Updated"), null));

            when(queryEngine.update(eq(contactsDef), eq("missing"), anyMap()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> executor.execute(List.of(op)))
                    .isInstanceOf(AtomicOperationExecutor.AtomicOperationException.class)
                    .hasMessageContaining("Record not found");
        }
    }

    @Nested
    @DisplayName("Remove Operations")
    class RemoveOperations {
        @Test
        void shouldDeleteAndReturnEmptyResult() {
            var op = new AtomicOperation("remove",
                    new AtomicOperation.ResourceRef("contacts", "123", null), null);

            when(queryEngine.delete(eq(contactsDef), eq("123"))).thenReturn(true);

            List<AtomicResult> results = executor.execute(List.of(op));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).data()).isNull();
        }

        @Test
        void shouldThrowWhenDeleteFails() {
            var op = new AtomicOperation("remove",
                    new AtomicOperation.ResourceRef("contacts", "missing", null), null);

            when(queryEngine.delete(eq(contactsDef), eq("missing"))).thenReturn(false);

            assertThatThrownBy(() -> executor.execute(List.of(op)))
                    .isInstanceOf(AtomicOperationExecutor.AtomicOperationException.class)
                    .hasMessageContaining("Record not found");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {
        @Test
        void shouldRejectUnknownOperationType() {
            var op = new AtomicOperation("merge", null,
                    new AtomicOperation.ResourceData("contacts", null, null, Map.of(), null));

            assertThatThrownBy(() -> executor.execute(List.of(op)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown op 'merge'");
        }

        @Test
        void shouldRejectMissingRefOnUpdate() {
            var op = new AtomicOperation("update", null,
                    new AtomicOperation.ResourceData("contacts", null, null, Map.of(), null));

            assertThatThrownBy(() -> executor.execute(List.of(op)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'update' requires 'ref'");
        }

        @Test
        void shouldRejectMissingDataOnAdd() {
            var op = new AtomicOperation("add", null, null);

            assertThatThrownBy(() -> executor.execute(List.of(op)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'add' requires 'data'");
        }

        @Test
        void shouldRejectUnknownCollectionType() {
            var op = new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData("nonexistent", null, null, Map.of(), null));

            when(collectionRegistry.get("nonexistent")).thenReturn(null);

            assertThatThrownBy(() -> executor.execute(List.of(op)))
                    .isInstanceOf(AtomicOperationExecutor.AtomicOperationException.class)
                    .hasMessageContaining("Unknown collection type");
        }

        @Test
        void shouldRejectUnresolvedLid() {
            var op = new AtomicOperation("update",
                    new AtomicOperation.ResourceRef("contacts", null, "nonexistent-lid"),
                    new AtomicOperation.ResourceData("contacts", null, null, Map.of(), null));

            assertThatThrownBy(() -> executor.execute(List.of(op)))
                    .isInstanceOf(AtomicOperationExecutor.AtomicOperationException.class)
                    .hasMessageContaining("Local ID 'nonexistent-lid' not found");
        }
    }

    @Nested
    @DisplayName("Error Propagation")
    class ErrorPropagation {
        @Test
        void shouldIncludeOperationIndexInException() {
            var op1 = new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData("contacts", null, null, Map.of("name", "A"), null));
            var op2 = new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData("contacts", null, null, Map.of("name", "B"), null));

            when(queryEngine.create(eq(contactsDef), anyMap()))
                    .thenReturn(Map.of("id", "1", "name", "A"))
                    .thenThrow(new RuntimeException("Validation failed"));

            assertThatThrownBy(() -> executor.execute(List.of(op1, op2)))
                    .isInstanceOf(AtomicOperationExecutor.AtomicOperationException.class)
                    .satisfies(ex -> {
                        var aoe = (AtomicOperationExecutor.AtomicOperationException) ex;
                        assertThat(aoe.getOperationIndex()).isEqualTo(1);
                    });
        }
    }

    @Nested
    @DisplayName("Audit Stamping")
    @SuppressWarnings("unchecked")
    class AuditStamping {
        private Map<String, Object> capturedCreate() {
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(eq(contactsDef), captor.capture());
            return captor.getValue();
        }

        @Test
        void addStampsCreatedByAndUpdatedBy() {
            var op = new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData("contacts", null, null, Map.of("name", "John"), null));
            when(queryEngine.create(eq(contactsDef), anyMap())).thenReturn(Map.of("id", "server-1"));

            executor.execute(List.of(op), "u-1");

            assertThat(capturedCreate())
                    .containsEntry("name", "John")
                    .containsEntry("createdBy", "u-1")
                    .containsEntry("updatedBy", "u-1");
        }

        @Test
        void updateStampsOnlyUpdatedBy() {
            var op = new AtomicOperation("update",
                    new AtomicOperation.ResourceRef("contacts", "123", null),
                    new AtomicOperation.ResourceData("contacts", null, null, Map.of("name", "Updated"), null));
            when(queryEngine.update(eq(contactsDef), eq("123"), anyMap()))
                    .thenReturn(Optional.of(Map.of("id", "123")));

            executor.execute(List.of(op), "u-1");

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).update(eq(contactsDef), eq("123"), captor.capture());
            assertThat(captor.getValue())
                    .containsExactlyInAnyOrderEntriesOf(Map.of("name", "Updated", "updatedBy", "u-1"));
        }

        @Test
        void addOverwritesClientSuppliedCreatedBy() {
            var op = new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData("contacts", null, null,
                            Map.of("name", "John", "createdBy", "spoofed", "updatedBy", "spoofed"), null));
            when(queryEngine.create(eq(contactsDef), anyMap())).thenReturn(Map.of("id", "server-1"));

            executor.execute(List.of(op), "u-1");

            assertThat(capturedCreate())
                    .containsEntry("createdBy", "u-1")
                    .containsEntry("updatedBy", "u-1");
        }

        @Test
        void nullActingUserLeavesAttributesUnchanged() {
            var op = new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData("contacts", null, null,
                            Map.of("name", "John", "createdBy", "client-value"), null));
            when(queryEngine.create(eq(contactsDef), anyMap())).thenReturn(Map.of("id", "server-1"));

            executor.execute(List.of(op), null);

            assertThat(capturedCreate())
                    .containsExactlyInAnyOrderEntriesOf(Map.of("name", "John", "createdBy", "client-value"));
        }
    }

    @Nested
    @DisplayName("Tenant Stamping")
    @SuppressWarnings("unchecked")
    class TenantStamping {
        private static final String TENANT = "11111111-1111-1111-1111-111111111111";

        private CollectionDefinition register(String name, boolean systemCollection, boolean tenantScoped) {
            CollectionDefinition def = new CollectionDefinitionBuilder()
                    .name(name).displayName(name)
                    .addField(FieldDefinition.string("name"))
                    .systemCollection(systemCollection)
                    .tenantScoped(tenantScoped)
                    .build();
            when(collectionRegistry.get(name)).thenReturn(def);
            return def;
        }

        private static AtomicOperation add(String type, Map<String, Object> attributes) {
            return new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData(type, null, null, attributes, null));
        }

        private Map<String, Object> capturedCreate(CollectionDefinition def) {
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(eq(def), captor.capture());
            return captor.getValue();
        }

        @Test
        void addOnTenantScopedSystemCollectionStampsBoundTenant() {
            CollectionDefinition def = register("list-views", true, true);
            when(queryEngine.create(eq(def), anyMap()))
                    .thenAnswer(inv -> {
                        Map<String, Object> row = new LinkedHashMap<>(inv.<Map<String, Object>>getArgument(1));
                        row.put("id", "lv-1");
                        return row;
                    });

            List<AtomicResult> results = TenantContext.callWithTenant(TENANT,
                    () -> executor.execute(List.of(add("list-views", Map.of("name", "Mine"))), "u-1"));

            assertThat(capturedCreate(def)).containsEntry("tenantId", TENANT).containsEntry("name", "Mine");
            assertThat(results.get(0).data().id()).isEqualTo("lv-1");
            assertThat(results.get(0).data().attributes()).containsEntry("tenantId", TENANT);
        }

        @Test
        void addKeepsCallerSuppliedTenantIdWhenItMatches() {
            CollectionDefinition def = register("list-views", true, true);
            when(queryEngine.create(eq(def), anyMap())).thenReturn(Map.of("id", "lv-1"));

            TenantContext.runWithTenant(TENANT, () -> executor.execute(
                    List.of(add("list-views", Map.of("name", "Mine", "tenantId", TENANT)))));

            assertThat(capturedCreate(def)).containsEntry("tenantId", TENANT);
        }

        @Test
        void addWithAnotherTenantsIdFailsThatOperation() {
            CollectionDefinition def = register("list-views", true, true);
            when(queryEngine.create(eq(def), anyMap())).thenReturn(Map.of("id", "lv-1"));
            var ops = List.of(
                    add("list-views", Map.of("name", "Mine")),
                    add("list-views", Map.of("name", "Theirs", "tenantId", "22222222-2222-2222-2222-222222222222")));

            assertThatThrownBy(() -> TenantContext.runWithTenant(TENANT, () -> executor.execute(ops)))
                    .isInstanceOf(AtomicOperationExecutor.AtomicOperationException.class)
                    .hasMessageContaining("does not match the request's tenant")
                    .satisfies(ex -> assertThat(((AtomicOperationExecutor.AtomicOperationException) ex)
                            .getOperationIndex()).isEqualTo(1));
            verify(queryEngine, times(1)).create(eq(def), anyMap());
        }

        @Test
        void userCollectionGetsNoTenantId() {
            CollectionDefinition def = register("projects", false, true);
            when(queryEngine.create(eq(def), anyMap())).thenReturn(Map.of("id", "p-1"));

            TenantContext.runWithTenant(TENANT, () -> executor.execute(List.of(add("projects", Map.of("name", "P")))));

            assertThat(capturedCreate(def)).doesNotContainKey("tenantId");
        }

        @Test
        void nonTenantScopedSystemCollectionGetsNoTenantId() {
            CollectionDefinition def = register("platform-things", true, false);
            when(queryEngine.create(eq(def), anyMap())).thenReturn(Map.of("id", "t-1"));

            TenantContext.runWithTenant(TENANT,
                    () -> executor.execute(List.of(add("platform-things", Map.of("name", "T")))));

            assertThat(capturedCreate(def)).doesNotContainKey("tenantId");
        }

        @Test
        void unboundTenantStampsNothing() {
            CollectionDefinition def = register("list-views", true, true);
            when(queryEngine.create(eq(def), anyMap())).thenReturn(Map.of("id", "lv-1"));

            executor.execute(List.of(add("list-views", Map.of("name", "Mine"))));

            assertThat(capturedCreate(def)).doesNotContainKey("tenantId");
        }
    }

    @Nested
    @DisplayName("Geo Stamping")
    @SuppressWarnings("unchecked")
    class GeoStampingOnOperations {
        private CollectionDefinition register(String name, boolean captureGeo) {
            CollectionDefinition def = new CollectionDefinitionBuilder()
                    .name(name).displayName(name)
                    .addField(FieldDefinition.string("name"))
                    .captureGeo(captureGeo)
                    .build();
            when(collectionRegistry.get(name)).thenReturn(def);
            return def;
        }

        private static Optional<GeoStamp> parsedGeo() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Geo-Country", "pt");
            request.addHeader("X-Geo-City", "Lisboa");
            request.addHeader("X-Geo-Lat", "38.72");
            request.addHeader("X-Geo-Lon", "-9.14");
            return GeoHeaders.parse(request);
        }

        private static AtomicOperation add(String type) {
            return new AtomicOperation("add", null,
                    new AtomicOperation.ResourceData(type, null, null, Map.of("name", "N"), null));
        }

        private static AtomicOperation update(String type, String id) {
            return new AtomicOperation("update", new AtomicOperation.ResourceRef(type, id, null),
                    new AtomicOperation.ResourceData(type, null, null, Map.of("name", "U"), null));
        }

        private Map<String, Object> capturedCreate(CollectionDefinition def) {
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).create(eq(def), captor.capture());
            return captor.getValue();
        }

        private Map<String, Object> capturedUpdate(CollectionDefinition def, String id) {
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(queryEngine).update(eq(def), eq(id), captor.capture());
            return captor.getValue();
        }

        @Test
        void addOnCaptureGeoCollectionStampsCreatedAndUpdatedGeo() {
            CollectionDefinition def = register("visits", true);
            when(queryEngine.create(eq(def), anyMap())).thenReturn(Map.of("id", "v-1"));
            Optional<GeoStamp> geo = parsedGeo();
            Map<String, Object> expected = Map.of("country", "PT", "city", "Lisboa", "lat", 38.72, "lon", -9.14);
            assertThat(geo.map(GeoStamp::toMap)).contains(expected);

            executor.execute(List.of(add("visits")), "u-1", geo);

            assertThat(capturedCreate(def))
                    .containsEntry("createdGeo", expected)
                    .containsEntry("updatedGeo", expected);
        }

        @Test
        void updateOnCaptureGeoCollectionStampsOnlyUpdatedGeo() {
            CollectionDefinition def = register("visits", true);
            when(queryEngine.update(eq(def), eq("v-1"), anyMap())).thenReturn(Optional.of(Map.of("id", "v-1")));

            executor.execute(List.of(update("visits", "v-1")), "u-1", parsedGeo());

            assertThat(capturedUpdate(def, "v-1"))
                    .containsEntry("updatedGeo", parsedGeo().orElseThrow().toMap())
                    .doesNotContainKey("createdGeo");
        }

        @Test
        void noGeoStampsNullAsTheRouterDoes() {
            CollectionDefinition def = register("visits", true);
            when(queryEngine.create(eq(def), anyMap())).thenReturn(Map.of("id", "v-1"));
            when(queryEngine.update(eq(def), eq("v-1"), anyMap())).thenReturn(Optional.of(Map.of("id", "v-1")));
            Optional<GeoStamp> noHeaders = GeoHeaders.parse(new MockHttpServletRequest());

            executor.execute(List.of(add("visits"), update("visits", "v-1")), "u-1", noHeaders);

            Map<String, Object> created = capturedCreate(def);
            assertThat(created).containsKey("createdGeo").containsKey("updatedGeo");
            assertThat(created.get("createdGeo")).isNull();
            assertThat(created.get("updatedGeo")).isNull();
            Map<String, Object> updated = capturedUpdate(def, "v-1");
            assertThat(updated).containsKey("updatedGeo").doesNotContainKey("createdGeo");
            assertThat(updated.get("updatedGeo")).isNull();
        }

        @Test
        void twoArgumentExecuteStampsNull() {
            CollectionDefinition def = register("visits", true);
            when(queryEngine.create(eq(def), anyMap())).thenReturn(Map.of("id", "v-1"));

            executor.execute(List.of(add("visits")), null);

            Map<String, Object> created = capturedCreate(def);
            assertThat(created).containsKey("createdGeo").containsKey("updatedGeo");
            assertThat(created.get("createdGeo")).isNull();
        }

        @Test
        void nonCaptureGeoCollectionGetsNeitherKey() {
            CollectionDefinition def = register("projects", false);
            when(queryEngine.create(eq(def), anyMap())).thenReturn(Map.of("id", "p-1"));
            when(queryEngine.update(eq(def), eq("p-1"), anyMap())).thenReturn(Optional.of(Map.of("id", "p-1")));

            executor.execute(List.of(add("projects"), update("projects", "p-1")), "u-1", parsedGeo());

            assertThat(capturedCreate(def)).doesNotContainKeys("createdGeo", "updatedGeo");
            assertThat(capturedUpdate(def, "p-1")).doesNotContainKeys("createdGeo", "updatedGeo");
        }
    }
}
