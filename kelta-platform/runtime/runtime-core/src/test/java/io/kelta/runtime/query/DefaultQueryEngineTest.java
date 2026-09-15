package io.kelta.runtime.query;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import io.kelta.runtime.formula.BuiltInFunctions;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.formula.FormulaFunction;
import io.kelta.runtime.model.*;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.CustomValidationRuleEngine;
import io.kelta.runtime.validation.DefaultValidationEngine;
import io.kelta.runtime.validation.OperationType;
import io.kelta.runtime.validation.RecordValidationException;
import io.kelta.runtime.validation.ValidationEngine;
import io.kelta.runtime.validation.ValidationError;
import io.kelta.runtime.validation.ValidationException;
import io.kelta.runtime.validation.ValidationResult;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DefaultQueryEngine.
 */
class DefaultQueryEngineTest {
    
    private StorageAdapter storageAdapter;
    private ValidationEngine validationEngine;
    private DefaultQueryEngine queryEngine;
    private CollectionDefinition testCollection;
    
    @BeforeEach
    void setUp() {
        storageAdapter = mock(StorageAdapter.class);
        validationEngine = mock(ValidationEngine.class);
        queryEngine = new DefaultQueryEngine(storageAdapter, validationEngine);
        
        testCollection = new CollectionDefinitionBuilder()
            .name("products")
            .displayName("Products")
            .addField(new FieldDefinitionBuilder()
                .name("name")
                .type(FieldType.STRING)
                .nullable(false)
                .build())
            .addField(new FieldDefinitionBuilder()
                .name("price")
                .type(FieldType.DOUBLE)
                .nullable(false)
                .build())
            .addField(new FieldDefinitionBuilder()
                .name("category")
                .type(FieldType.STRING)
                .nullable(true)
                .build())
            .build();
    }
    
    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        
        @Test
        @DisplayName("Should create engine with storage adapter only")
        void shouldCreateWithStorageAdapterOnly() {
            DefaultQueryEngine engine = new DefaultQueryEngine(storageAdapter);
            assertNotNull(engine);
        }
        
        @Test
        @DisplayName("Should throw on null storage adapter")
        void shouldThrowOnNullStorageAdapter() {
            assertThrows(NullPointerException.class, () -> 
                new DefaultQueryEngine(null));
        }
    }
    
    @Nested
    @DisplayName("executeQuery Tests")
    class ExecuteQueryTests {
        
        @Test
        @DisplayName("Should execute query with valid parameters")
        void shouldExecuteQueryWithValidParameters() {
            QueryRequest request = QueryRequest.defaults();
            QueryResult expectedResult = QueryResult.empty(request.pagination());
            
            when(storageAdapter.query(testCollection, request)).thenReturn(expectedResult);
            
            QueryResult result = queryEngine.executeQuery(testCollection, request);
            
            assertEquals(expectedResult, result);
            verify(storageAdapter).query(testCollection, request);
        }
        
        @Test
        @DisplayName("Should validate sort fields")
        void shouldValidateSortFields() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(new SortField("nonexistent", SortDirection.ASC)),
                List.of(),
                List.of()
            );
            
            InvalidQueryException ex = assertThrows(InvalidQueryException.class, () ->
                queryEngine.executeQuery(testCollection, request));
            
            assertEquals("nonexistent", ex.getFieldName());
        }
        
        @Test
        @DisplayName("Should allow sorting by system fields")
        void shouldAllowSortingBySystemFields() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(new SortField("createdAt", SortDirection.DESC)),
                List.of(),
                List.of()
            );
            QueryResult expectedResult = QueryResult.empty(request.pagination());
            
            when(storageAdapter.query(testCollection, request)).thenReturn(expectedResult);
            
            QueryResult result = queryEngine.executeQuery(testCollection, request);
            
            assertEquals(expectedResult, result);
        }

        @Test
        @DisplayName("Should validate filter fields")
        void shouldValidateFilterFields() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("nonexistent", FilterOperator.EQ, "value"))
            );
            
            InvalidQueryException ex = assertThrows(InvalidQueryException.class, () ->
                queryEngine.executeQuery(testCollection, request));
            
            assertEquals("nonexistent", ex.getFieldName());
        }
        
        @Test
        @DisplayName("Should validate requested fields")
        void shouldValidateRequestedFields() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of("nonexistent"),
                List.of()
            );
            
            InvalidQueryException ex = assertThrows(InvalidQueryException.class, () ->
                queryEngine.executeQuery(testCollection, request));
            
            assertEquals("nonexistent", ex.getFieldName());
        }
        
        @Test
        @DisplayName("Should allow requesting system fields")
        void shouldAllowRequestingSystemFields() {
            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of("id", "createdAt", "updatedAt"),
                List.of()
            );
            QueryResult expectedResult = QueryResult.empty(request.pagination());
            
            when(storageAdapter.query(testCollection, request)).thenReturn(expectedResult);
            
            QueryResult result = queryEngine.executeQuery(testCollection, request);
            
            assertEquals(expectedResult, result);
        }

        @Test
        @DisplayName("Should allow tenantId filter on tenant-scoped collections")
        void shouldAllowTenantIdFilterOnTenantScopedCollections() {
            CollectionDefinition tenantScopedCollection = new CollectionDefinitionBuilder()
                .name("reports")
                .displayName("Reports")
                .systemCollection(true)
                .tenantScoped(true)
                .addField(new FieldDefinitionBuilder()
                    .name("name")
                    .type(FieldType.STRING)
                    .nullable(false)
                    .build())
                .build();

            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("tenantId", FilterOperator.EQ, "tenant-123"))
            );
            QueryResult expectedResult = QueryResult.empty(request.pagination());

            when(storageAdapter.query(tenantScopedCollection, request)).thenReturn(expectedResult);

            QueryResult result = queryEngine.executeQuery(tenantScopedCollection, request);

            assertEquals(expectedResult, result);
        }

        @Test
        @DisplayName("Should reject tenantId filter on non-tenant-scoped collections")
        void shouldRejectTenantIdFilterOnNonTenantScopedCollections() {
            CollectionDefinition nonTenantCollection = new CollectionDefinitionBuilder()
                .name("action-types")
                .displayName("Action Types")
                .systemCollection(true)
                .tenantScoped(false)
                .addField(new FieldDefinitionBuilder()
                    .name("name")
                    .type(FieldType.STRING)
                    .nullable(false)
                    .build())
                .build();

            QueryRequest request = new QueryRequest(
                Pagination.defaults(),
                List.of(),
                List.of(),
                List.of(new FilterCondition("tenantId", FilterOperator.EQ, "tenant-123"))
            );

            InvalidQueryException ex = assertThrows(InvalidQueryException.class, () ->
                queryEngine.executeQuery(nonTenantCollection, request));

            assertEquals("tenantId", ex.getFieldName());
        }

        @Test
        @DisplayName("Should throw on null definition")
        void shouldThrowOnNullDefinition() {
            assertThrows(NullPointerException.class, () ->
                queryEngine.executeQuery(null, QueryRequest.defaults()));
        }

        @Test
        @DisplayName("Should throw on null request")
        void shouldThrowOnNullRequest() {
            assertThrows(NullPointerException.class, () ->
                queryEngine.executeQuery(testCollection, null));
        }
    }
    
    @Nested
    @DisplayName("getById Tests")
    class GetByIdTests {
        
        @Test
        @DisplayName("Should return record when found")
        void shouldReturnRecordWhenFound() {
            String id = "test-id";
            Map<String, Object> record = Map.of("id", id, "name", "Test Product");
            
            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(record));
            
            Optional<Map<String, Object>> result = queryEngine.getById(testCollection, id);
            
            assertTrue(result.isPresent());
            assertEquals(record, result.get());
        }
        
        @Test
        @DisplayName("Should return empty when not found")
        void shouldReturnEmptyWhenNotFound() {
            String id = "nonexistent";
            
            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.empty());
            
            Optional<Map<String, Object>> result = queryEngine.getById(testCollection, id);
            
            assertTrue(result.isEmpty());
        }
    }
    
    @Nested
    @DisplayName("create Tests")
    class CreateTests {
        
        @Test
        @DisplayName("Should add system fields on create")
        void shouldAddSystemFieldsOnCreate() {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test Product");
            inputData.put("price", 99.99);
            
            when(validationEngine.validate(eq(testCollection), any(), any()))
                .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.create(eq(testCollection), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
            
            Map<String, Object> result = queryEngine.create(testCollection, inputData);
            
            assertNotNull(result.get("id"));
            assertNotNull(result.get("createdAt"));
            assertNotNull(result.get("updatedAt"));
            assertEquals("Test Product", result.get("name"));
        }
        
        @Test
        @DisplayName("Should validate data before create")
        void shouldValidateDataBeforeCreate() {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test");
            
            io.kelta.runtime.validation.ValidationResult invalidResult = 
                io.kelta.runtime.validation.ValidationResult.failure(
                    io.kelta.runtime.validation.FieldError.nullable("price")
                );
            
            when(validationEngine.validate(eq(testCollection), any(), any()))
                .thenReturn(invalidResult);
            
            assertThrows(ValidationException.class, () ->
                queryEngine.create(testCollection, inputData));
            
            verify(storageAdapter, never()).create(any(), any());
        }
        
        @Test
        @DisplayName("Should work without validation engine")
        void shouldWorkWithoutValidationEngine() {
            DefaultQueryEngine engineNoValidation = new DefaultQueryEngine(storageAdapter);
            
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test Product");
            
            when(storageAdapter.create(eq(testCollection), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
            
            Map<String, Object> result = engineNoValidation.create(testCollection, inputData);
            
            assertNotNull(result.get("id"));
        }
    }

    @Nested
    @DisplayName("update Tests")
    class UpdateTests {
        
        @Test
        @DisplayName("Should update existing record")
        void shouldUpdateExistingRecord() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Old Name");
            existingRecord.put("price", 50.0);
            existingRecord.put("createdAt", Instant.now().minusSeconds(3600));
            
            Map<String, Object> updateData = new HashMap<>();
            updateData.put("name", "New Name");
            
            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(testCollection), any(), any(), eq(id)))
                .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.update(eq(testCollection), eq(id), any()))
                .thenAnswer(invocation -> {
                    Map<String, Object> data = invocation.getArgument(2);
                    Map<String, Object> result = new HashMap<>(existingRecord);
                    result.putAll(data);
                    return Optional.of(result);
                });
            
            Optional<Map<String, Object>> result = queryEngine.update(testCollection, id, updateData);

            assertTrue(result.isPresent());
            assertEquals("New Name", result.get().get("name"));
            assertNotNull(result.get().get("updatedAt"));
        }

        @Test
        @DisplayName("Field validation sees only the patch, not the merged record (2026-07-12: merged DATE re-validation)")
        void validatesOnlyThePatchNotMergedRecord() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Old Name");
            existingRecord.put("price", 50.0);

            Map<String, Object> updateData = new HashMap<>();
            updateData.put("name", "New Name");

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(testCollection), any(), any(), eq(id)))
                .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.update(eq(testCollection), eq(id), any()))
                .thenReturn(Optional.of(existingRecord));

            queryEngine.update(testCollection, id, updateData);

            ArgumentCaptor<Map<String, Object>> validated = ArgumentCaptor.forClass(Map.class);
            verify(validationEngine).validate(eq(testCollection), validated.capture(), any(), eq(id));
            // Only the patched field reaches validation — untouched stored columns
            // (whose read-back form may not match their input form) are not re-checked.
            assertTrue(validated.getValue().containsKey("name"));
            assertFalse(validated.getValue().containsKey("price"),
                "merged stored fields must not leak into UPDATE field-validation");
        }

        @Test
        @DisplayName("End-to-end: PATCH an unrelated field on a record with a populated DATE succeeds (real validator)")
        void patchUnrelatedFieldWithStoredDateSucceeds() {
            CollectionDefinition invoices = new CollectionDefinitionBuilder()
                .name("invoices")
                .displayName("Invoices")
                .addField(new FieldDefinitionBuilder()
                    .name("status").type(FieldType.STRING).nullable(false).build())
                .addField(new FieldDefinitionBuilder()
                    .name("issueDate").type(FieldType.DATE).nullable(true).build())
                .build();

            DefaultQueryEngine engineRealValidation = new DefaultQueryEngine(
                storageAdapter, new io.kelta.runtime.validation.DefaultValidationEngine(storageAdapter));

            String id = "inv-1";
            Map<String, Object> stored = new HashMap<>();
            stored.put("id", id);
            stored.put("status", "ISSUED");
            // DATE column reads back as a midnight datetime through the JSON layer.
            stored.put("issueDate", "2026-07-12T00:00:00.000Z");

            when(storageAdapter.getById(invoices, id)).thenReturn(Optional.of(stored));
            when(storageAdapter.update(eq(invoices), eq(id), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> merged = new HashMap<>(stored);
                    merged.putAll(inv.getArgument(2));
                    return Optional.of(merged);
                });

            // Before the fix this threw ValidationException (issueDate "expected DATE").
            Optional<Map<String, Object>> result =
                engineRealValidation.update(invoices, id, Map.of("status", "PAID"));

            assertTrue(result.isPresent());
            assertEquals("PAID", result.get().get("status"));
        }

        @Test
        @DisplayName("Should return empty when record not found")
        void shouldReturnEmptyWhenRecordNotFound() {
            String id = "nonexistent";
            Map<String, Object> updateData = Map.of("name", "New Name");
            
            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.empty());
            
            Optional<Map<String, Object>> result = queryEngine.update(testCollection, id, updateData);
            
            assertTrue(result.isEmpty());
            verify(storageAdapter, never()).update(any(), any(), any());
        }
        
        @Test
        @DisplayName("Should not allow changing id")
        void shouldNotAllowChangingId() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Test");
            
            Map<String, Object> updateData = new HashMap<>();
            updateData.put("id", "new-id");
            updateData.put("name", "New Name");
            
            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(testCollection), any(), any(), eq(id)))
                .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.update(eq(testCollection), eq(id), any()))
                .thenAnswer(invocation -> {
                    Map<String, Object> data = invocation.getArgument(2);
                    assertFalse(data.containsKey("id"), "id should be removed from update data");
                    return Optional.of(new HashMap<>(existingRecord));
                });
            
            queryEngine.update(testCollection, id, updateData);
            
            verify(storageAdapter).update(eq(testCollection), eq(id), argThat(data -> 
                !data.containsKey("id")));
        }
        
        @Test
        @DisplayName("Should validate data before update")
        void shouldValidateDataBeforeUpdate() {
            String id = "test-id";
            Map<String, Object> existingRecord = Map.of("id", id, "name", "Test");
            Map<String, Object> updateData = new HashMap<>();
            updateData.put("name", "");
            
            io.kelta.runtime.validation.ValidationResult invalidResult = 
                io.kelta.runtime.validation.ValidationResult.failure(List.of(
                    new io.kelta.runtime.validation.FieldError("name", "Cannot be empty", "minLength")
                ));
            
            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(testCollection), any(), any(), eq(id)))
                .thenReturn(invalidResult);
            
            assertThrows(ValidationException.class, () ->
                queryEngine.update(testCollection, id, updateData));
            
            verify(storageAdapter, never()).update(any(), any(), any());
        }
    }
    
    @Nested
    @DisplayName("delete Tests")
    class DeleteTests {

        @Test
        @DisplayName("Should delete existing record")
        void shouldDeleteExistingRecord() {
            String id = "test-id";

            when(storageAdapter.delete(testCollection, id)).thenReturn(true);

            boolean result = queryEngine.delete(testCollection, id);

            assertTrue(result);
            verify(storageAdapter).delete(testCollection, id);
        }

        @Test
        @DisplayName("Should return false when record not found")
        void shouldReturnFalseWhenRecordNotFound() {
            String id = "nonexistent";

            when(storageAdapter.delete(testCollection, id)).thenReturn(false);

            boolean result = queryEngine.delete(testCollection, id);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("Custom Validation Rule Tests")
    class CustomValidationRuleTests {

        private CustomValidationRuleEngine customRuleEngine;
        private DefaultQueryEngine engineWithRules;

        @BeforeEach
        void setUp() {
            customRuleEngine = mock(CustomValidationRuleEngine.class);
            engineWithRules = new DefaultQueryEngine(
                    storageAdapter, validationEngine, null, null, null, null, customRuleEngine);
        }

        @Test
        @DisplayName("Should evaluate custom rules on create")
        void shouldEvaluateCustomRulesOnCreate() {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test");
            inputData.put("price", 10.0);

            when(validationEngine.validate(eq(testCollection), any(), any()))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.create(eq(testCollection), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            engineWithRules.create(testCollection, inputData);

            verify(customRuleEngine).evaluate(
                    eq("products"), any(), eq(OperationType.CREATE));
        }

        @Test
        @DisplayName("Should throw RecordValidationException when custom rule fails on create")
        void shouldThrowWhenCustomRuleFailsOnCreate() {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test");
            inputData.put("price", -5.0);

            when(validationEngine.validate(eq(testCollection), any(), any()))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            doThrow(new RecordValidationException(List.of(
                    new ValidationError("positive_price", "Price must be positive", "price"))))
                    .when(customRuleEngine).evaluate(eq("products"), any(), eq(OperationType.CREATE));

            assertThrows(RecordValidationException.class, () ->
                    engineWithRules.create(testCollection, inputData));

            verify(storageAdapter, never()).create(any(), any());
        }

        @Test
        @DisplayName("Should evaluate custom rules on update with merged data")
        void shouldEvaluateCustomRulesOnUpdate() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Test");
            existingRecord.put("price", 50.0);

            Map<String, Object> updateData = new HashMap<>();
            updateData.put("price", 99.0);

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(testCollection), any(), any(), eq(id)))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.update(eq(testCollection), eq(id), any()))
                    .thenAnswer(invocation -> Optional.of(invocation.getArgument(2)));

            engineWithRules.update(testCollection, id, updateData);

            verify(customRuleEngine).evaluate(
                    eq("products"), any(), eq(OperationType.UPDATE));
        }

        @Test
        @DisplayName("Should throw RecordValidationException when custom rule fails on update")
        void shouldThrowWhenCustomRuleFailsOnUpdate() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Test");
            existingRecord.put("price", 50.0);

            Map<String, Object> updateData = new HashMap<>();
            updateData.put("price", -10.0);

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(testCollection), any(), any(), eq(id)))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            doThrow(new RecordValidationException(List.of(
                    new ValidationError("positive_price", "Price must be positive", "price"))))
                    .when(customRuleEngine).evaluate(eq("products"), any(), eq(OperationType.UPDATE));

            assertThrows(RecordValidationException.class, () ->
                    engineWithRules.update(testCollection, id, updateData));

            verify(storageAdapter, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("Should skip custom rules when engine is null")
        void shouldSkipCustomRulesWhenEngineIsNull() {
            // The default queryEngine has no customRuleEngine
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test");
            inputData.put("price", 10.0);

            when(validationEngine.validate(eq(testCollection), any(), any()))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.create(eq(testCollection), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            assertDoesNotThrow(() ->
                    queryEngine.create(testCollection, inputData));
        }
    }

    @Nested
    @DisplayName("Record Event Publishing Tests")
    class RecordEventPublishingTests {

        private RecordEventPublisher recordEventPublisher;
        private DefaultQueryEngine engineWithPublisher;

        @BeforeEach
        void setUp() {
            recordEventPublisher = mock(RecordEventPublisher.class);
            engineWithPublisher = new DefaultQueryEngine(
                    storageAdapter, validationEngine, null, null, null, null, null,
                    recordEventPublisher);
        }

        @Test
        @DisplayName("Should publish CREATED event after successful create")
        void shouldPublishCreatedEventAfterCreate() {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test Product");
            inputData.put("price", 99.99);

            when(validationEngine.validate(eq(testCollection), any(), any()))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.create(eq(testCollection), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            engineWithPublisher.create(testCollection, inputData);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> eventCaptor =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(recordEventPublisher).publish(eventCaptor.capture());

            PlatformEvent<RecordChangedPayload> event = eventCaptor.getValue();
            assertNotNull(event.getEventId());
            RecordChangedPayload payload = event.getPayload();
            assertEquals(ChangeType.CREATED, payload.getChangeType());
            assertEquals("products", payload.getCollectionName());
            assertNotNull(payload.getRecordId());
            assertNotNull(payload.getData());
            assertEquals("Test Product", payload.getData().get("name"));
            assertNull(payload.getPreviousData());
            assertTrue(payload.getChangedFields().isEmpty());
        }

        @Test
        @DisplayName("Should not publish event when publisher is null")
        void shouldNotPublishEventWhenPublisherIsNull() {
            // The default queryEngine has no publisher
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test");
            inputData.put("price", 10.0);

            when(validationEngine.validate(eq(testCollection), any(), any()))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.create(eq(testCollection), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            assertDoesNotThrow(() ->
                    queryEngine.create(testCollection, inputData));
            // No publisher to verify — just ensuring no NPE
        }

        @Test
        @DisplayName("Should publish UPDATED event with changed fields after successful update")
        void shouldPublishUpdatedEventAfterUpdate() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Old Name");
            existingRecord.put("price", 50.0);
            existingRecord.put("createdAt", Instant.now().minusSeconds(3600));

            Map<String, Object> updateData = new HashMap<>();
            updateData.put("name", "New Name");

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(testCollection), any(), any(), eq(id)))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.update(eq(testCollection), eq(id), any()))
                    .thenAnswer(invocation -> {
                        Map<String, Object> data = invocation.getArgument(2);
                        Map<String, Object> result = new HashMap<>(existingRecord);
                        result.putAll(data);
                        return Optional.of(result);
                    });

            engineWithPublisher.update(testCollection, id, updateData);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> eventCaptor =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(recordEventPublisher).publish(eventCaptor.capture());

            PlatformEvent<RecordChangedPayload> event = eventCaptor.getValue();
            RecordChangedPayload payload = event.getPayload();
            assertEquals(ChangeType.UPDATED, payload.getChangeType());
            assertEquals("products", payload.getCollectionName());
            assertEquals(id, payload.getRecordId());
            assertNotNull(payload.getData());
            assertNotNull(payload.getPreviousData());
            assertEquals("Old Name", payload.getPreviousData().get("name"));
            assertTrue(payload.getChangedFields().contains("name"));
        }

        @Test
        @DisplayName("Should not publish event when update record not found")
        void shouldNotPublishEventWhenUpdateRecordNotFound() {
            String id = "nonexistent";
            Map<String, Object> updateData = Map.of("name", "New Name");

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.empty());

            Optional<Map<String, Object>> result = engineWithPublisher.update(testCollection, id, updateData);

            assertTrue(result.isEmpty());
            verify(recordEventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("Should publish DELETED event after successful delete")
        void shouldPublishDeletedEventAfterDelete() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Test Product");

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(storageAdapter.delete(testCollection, id)).thenReturn(true);

            boolean result = engineWithPublisher.delete(testCollection, id);

            assertTrue(result);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> eventCaptor =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(recordEventPublisher).publish(eventCaptor.capture());

            PlatformEvent<RecordChangedPayload> event = eventCaptor.getValue();
            RecordChangedPayload payload = event.getPayload();
            assertEquals(ChangeType.DELETED, payload.getChangeType());
            assertEquals("products", payload.getCollectionName());
            assertEquals(id, payload.getRecordId());
            assertNotNull(payload.getData());
            assertEquals("Test Product", payload.getData().get("name"));
            assertNull(payload.getPreviousData());
        }

        @Test
        @DisplayName("Should not publish event when delete returns false")
        void shouldNotPublishEventWhenDeleteReturnsFalse() {
            String id = "nonexistent";

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.empty());
            when(storageAdapter.delete(testCollection, id)).thenReturn(false);

            boolean result = engineWithPublisher.delete(testCollection, id);

            assertFalse(result);
            verify(recordEventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("Should not fail CRUD when event publishing throws exception")
        void shouldNotFailCrudWhenPublishingThrows() {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test");
            inputData.put("price", 10.0);

            when(validationEngine.validate(eq(testCollection), any(), any()))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.create(eq(testCollection), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            doThrow(new RuntimeException("NATS is down"))
                    .when(recordEventPublisher).publish(any());

            // Should not throw despite publisher failure
            Map<String, Object> result = assertDoesNotThrow(() ->
                    engineWithPublisher.create(testCollection, inputData));
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should not pre-fetch record on delete when publisher is null")
        void shouldNotPreFetchOnDeleteWhenPublisherIsNull() {
            String id = "test-id";

            when(storageAdapter.delete(testCollection, id)).thenReturn(true);

            queryEngine.delete(testCollection, id);

            // Should not call getById when publisher is null (no pre-fetch needed)
            verify(storageAdapter, never()).getById(any(), any());
            verify(storageAdapter).delete(testCollection, id);
        }

        @Test
        @DisplayName("Should include tenant ID from record data in event")
        void shouldIncludeTenantIdInEvent() {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test");
            inputData.put("price", 10.0);
            inputData.put("tenantId", "tenant-123");

            when(validationEngine.validate(eq(testCollection), any(), any()))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.create(eq(testCollection), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            engineWithPublisher.create(testCollection, inputData);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> eventCaptor =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(recordEventPublisher).publish(eventCaptor.capture());

            assertEquals("tenant-123", eventCaptor.getValue().getTenantId());
        }

        @Test
        @DisplayName("Should compute changed fields correctly for update")
        void shouldComputeChangedFieldsCorrectly() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Same Name");
            existingRecord.put("price", 50.0);
            existingRecord.put("category", "Electronics");

            // Update only price and category; name stays the same
            Map<String, Object> updateData = new HashMap<>();
            updateData.put("name", "Same Name");
            updateData.put("price", 99.0);
            updateData.put("category", "Gadgets");

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(testCollection), any(), any(), eq(id)))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.update(eq(testCollection), eq(id), any()))
                    .thenAnswer(invocation -> Optional.of(new HashMap<>(existingRecord)));

            engineWithPublisher.update(testCollection, id, updateData);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> eventCaptor =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(recordEventPublisher).publish(eventCaptor.capture());

            RecordChangedPayload eventPayload = eventCaptor.getValue().getPayload();
            List<String> changedFields = eventPayload.getChangedFields();
            assertTrue(changedFields.contains("price"), "price should be in changedFields");
            assertTrue(changedFields.contains("category"), "category should be in changedFields");
            assertFalse(changedFields.contains("name"), "name should NOT be in changedFields (unchanged)");
            assertFalse(changedFields.contains("updatedAt"), "updatedAt should NOT be in changedFields");
        }
    }

    @Nested
    @DisplayName("Contains Masked Fields Flag Tests")
    class ContainsMaskedFieldsFlagTests {

        private RecordEventPublisher recordEventPublisher;
        private DefaultQueryEngine engineWithPublisher;
        private CollectionDefinition maskedCollection;

        @BeforeEach
        void setUp() {
            recordEventPublisher = mock(RecordEventPublisher.class);
            engineWithPublisher = new DefaultQueryEngine(
                    storageAdapter, validationEngine, null, null, null, null, null,
                    recordEventPublisher);

            maskedCollection = new CollectionDefinitionBuilder()
                .name("contacts")
                .displayName("Contacts")
                .addField(new FieldDefinitionBuilder()
                    .name("name")
                    .type(FieldType.STRING)
                    .nullable(false)
                    .build())
                .addField(new FieldDefinitionBuilder()
                    .name("ssn")
                    .type(FieldType.STRING)
                    .nullable(true)
                    .fieldTypeConfig(Map.of("masking", Map.of("type", "FULL")))
                    .build())
                .build();
        }

        private RecordChangedPayload capturePublishedPayload() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<PlatformEvent<RecordChangedPayload>> eventCaptor =
                    ArgumentCaptor.forClass(PlatformEvent.class);
            verify(recordEventPublisher).publish(eventCaptor.capture());
            return eventCaptor.getValue().getPayload();
        }

        @Test
        @DisplayName("Should flag containsMaskedFields on CREATED event when a field has masking config")
        void shouldFlagContainsMaskedFieldsOnCreate() {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Jane");
            inputData.put("ssn", "123-45-6789");

            when(validationEngine.validate(eq(maskedCollection), any(), any()))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.create(eq(maskedCollection), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            engineWithPublisher.create(maskedCollection, inputData);

            RecordChangedPayload payload = capturePublishedPayload();
            assertEquals(ChangeType.CREATED, payload.getChangeType());
            assertTrue(payload.isContainsMaskedFields());
        }

        @Test
        @DisplayName("Should not flag containsMaskedFields on CREATED event without masking config")
        void shouldNotFlagContainsMaskedFieldsOnCreateWithoutMaskingConfig() {
            Map<String, Object> inputData = new HashMap<>();
            inputData.put("name", "Test");
            inputData.put("price", 10.0);

            when(validationEngine.validate(eq(testCollection), any(), any()))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.create(eq(testCollection), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            engineWithPublisher.create(testCollection, inputData);

            RecordChangedPayload payload = capturePublishedPayload();
            assertEquals(ChangeType.CREATED, payload.getChangeType());
            assertFalse(payload.isContainsMaskedFields());
        }

        @Test
        @DisplayName("Should flag containsMaskedFields on UPDATED event when a field has masking config")
        void shouldFlagContainsMaskedFieldsOnUpdate() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Old Name");
            existingRecord.put("ssn", "123-45-6789");

            Map<String, Object> updateData = new HashMap<>();
            updateData.put("name", "New Name");

            when(storageAdapter.getById(maskedCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(maskedCollection), any(), any(), eq(id)))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.update(eq(maskedCollection), eq(id), any()))
                    .thenAnswer(invocation -> {
                        Map<String, Object> data = invocation.getArgument(2);
                        Map<String, Object> result = new HashMap<>(existingRecord);
                        result.putAll(data);
                        return Optional.of(result);
                    });

            engineWithPublisher.update(maskedCollection, id, updateData);

            RecordChangedPayload payload = capturePublishedPayload();
            assertEquals(ChangeType.UPDATED, payload.getChangeType());
            assertTrue(payload.isContainsMaskedFields());
        }

        @Test
        @DisplayName("Should not flag containsMaskedFields on UPDATED event without masking config")
        void shouldNotFlagContainsMaskedFieldsOnUpdateWithoutMaskingConfig() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Old Name");
            existingRecord.put("price", 50.0);

            Map<String, Object> updateData = new HashMap<>();
            updateData.put("name", "New Name");

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(validationEngine.validate(eq(testCollection), any(), any(), eq(id)))
                    .thenReturn(io.kelta.runtime.validation.ValidationResult.success());
            when(storageAdapter.update(eq(testCollection), eq(id), any()))
                    .thenAnswer(invocation -> {
                        Map<String, Object> data = invocation.getArgument(2);
                        Map<String, Object> result = new HashMap<>(existingRecord);
                        result.putAll(data);
                        return Optional.of(result);
                    });

            engineWithPublisher.update(testCollection, id, updateData);

            RecordChangedPayload payload = capturePublishedPayload();
            assertEquals(ChangeType.UPDATED, payload.getChangeType());
            assertFalse(payload.isContainsMaskedFields());
        }

        @Test
        @DisplayName("Should flag containsMaskedFields on DELETED event when a field has masking config")
        void shouldFlagContainsMaskedFieldsOnDelete() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Jane");
            existingRecord.put("ssn", "123-45-6789");

            when(storageAdapter.getById(maskedCollection, id)).thenReturn(Optional.of(existingRecord));
            when(storageAdapter.delete(maskedCollection, id)).thenReturn(true);

            engineWithPublisher.delete(maskedCollection, id);

            RecordChangedPayload payload = capturePublishedPayload();
            assertEquals(ChangeType.DELETED, payload.getChangeType());
            assertTrue(payload.isContainsMaskedFields());
        }

        @Test
        @DisplayName("Should not flag containsMaskedFields on DELETED event without masking config")
        void shouldNotFlagContainsMaskedFieldsOnDeleteWithoutMaskingConfig() {
            String id = "test-id";
            Map<String, Object> existingRecord = new HashMap<>();
            existingRecord.put("id", id);
            existingRecord.put("name", "Test Product");

            when(storageAdapter.getById(testCollection, id)).thenReturn(Optional.of(existingRecord));
            when(storageAdapter.delete(testCollection, id)).thenReturn(true);

            engineWithPublisher.delete(testCollection, id);

            RecordChangedPayload payload = capturePublishedPayload();
            assertEquals(ChangeType.DELETED, payload.getChangeType());
            assertFalse(payload.isContainsMaskedFields());
        }
    }

    @Nested
    @DisplayName("BeforeSaveHook Integration Tests")
    class BeforeSaveHookTests {

        private BeforeSaveHookRegistry hookRegistry;
        private DefaultQueryEngine engineWithHooks;
        private ValidationEngine hookValidationEngine;

        @BeforeEach
        void setUp() {
            hookRegistry = new BeforeSaveHookRegistry();
            hookValidationEngine = mock(ValidationEngine.class);
            // Default: validation passes for both 3-arg and 4-arg overloads
            when(hookValidationEngine.validate(any(CollectionDefinition.class), anyMap(), any(OperationType.class)))
                .thenReturn(ValidationResult.success());
            when(hookValidationEngine.validate(any(CollectionDefinition.class), anyMap(), any(OperationType.class), any()))
                .thenCallRealMethod();
            engineWithHooks = new DefaultQueryEngine(
                storageAdapter, hookValidationEngine, null, null, null, null, null, null, hookRegistry);
        }

        @Test
        @DisplayName("Should call before-create hook during create")
        void shouldCallBeforeCreateHook() {
            AtomicBoolean hookCalled = new AtomicBoolean(false);
            hookRegistry.register(new BeforeSaveHook() {
                @Override
                public String getCollectionName() { return "products"; }
                @Override
                public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
                    hookCalled.set(true);
                    return BeforeSaveResult.ok();
                }
            });

            Map<String, Object> data = new HashMap<>(Map.of("name", "Widget", "price", 9.99));
            when(storageAdapter.create(any(CollectionDefinition.class), any())).thenAnswer(inv -> inv.getArgument(1));

            engineWithHooks.create(testCollection, data);
            assertTrue(hookCalled.get(), "Before-create hook should have been called");
        }

        @Test
        @DisplayName("Should apply field updates from before-create hook")
        void shouldApplyFieldUpdatesFromBeforeCreateHook() {
            hookRegistry.register(new BeforeSaveHook() {
                @Override
                public String getCollectionName() { return "products"; }
                @Override
                public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
                    return BeforeSaveResult.withFieldUpdates(Map.of("category", "DEFAULT"));
                }
            });

            Map<String, Object> data = new HashMap<>(Map.of("name", "Widget", "price", 9.99));
            when(storageAdapter.create(any(CollectionDefinition.class), any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> savedData = (Map<String, Object>) inv.getArgument(1);
                return savedData;
            });

            Map<String, Object> result = engineWithHooks.create(testCollection, data);
            assertEquals("DEFAULT", result.get("category"), "Field update from hook should be applied");
        }

        @Test
        @DisplayName("Should block create when before-create hook returns error")
        void shouldBlockCreateOnHookError() {
            hookRegistry.register(new BeforeSaveHook() {
                @Override
                public String getCollectionName() { return "products"; }
                @Override
                public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
                    return BeforeSaveResult.error("name", "Product name is forbidden");
                }
            });

            Map<String, Object> data = new HashMap<>(Map.of("name", "Forbidden", "price", 0.0));

            assertThrows(ValidationException.class, () ->
                engineWithHooks.create(testCollection, data));

            // Verify storage was never called
            verify(storageAdapter, never()).create(any(CollectionDefinition.class), any());
        }

        @Test
        @DisplayName("Should call before-update hook during update")
        void shouldCallBeforeUpdateHook() {
            AtomicBoolean hookCalled = new AtomicBoolean(false);
            hookRegistry.register(new BeforeSaveHook() {
                @Override
                public String getCollectionName() { return "products"; }
                @Override
                public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                                      Map<String, Object> previous, String tenantId) {
                    hookCalled.set(true);
                    return BeforeSaveResult.ok();
                }
            });

            String id = UUID.randomUUID().toString();
            Map<String, Object> existingRecord = new HashMap<>(Map.of(
                "id", id, "name", "Widget", "price", 9.99, "createdAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString()));

            when(storageAdapter.getById(any(CollectionDefinition.class), eq(id)))
                .thenReturn(Optional.of(existingRecord));
            when(storageAdapter.update(any(CollectionDefinition.class), eq(id), any()))
                .thenReturn(Optional.of(existingRecord));

            Map<String, Object> updateData = new HashMap<>(Map.of("price", 19.99));
            engineWithHooks.update(testCollection, id, updateData);
            assertTrue(hookCalled.get(), "Before-update hook should have been called");
        }

        @Test
        @DisplayName("Should block update when before-update hook returns error")
        void shouldBlockUpdateOnHookError() {
            hookRegistry.register(new BeforeSaveHook() {
                @Override
                public String getCollectionName() { return "products"; }
                @Override
                public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                                      Map<String, Object> previous, String tenantId) {
                    return BeforeSaveResult.error("price", "Price cannot be negative");
                }
            });

            String id = UUID.randomUUID().toString();
            Map<String, Object> existingRecord = new HashMap<>(Map.of(
                "id", id, "name", "Widget", "price", 9.99, "createdAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString()));

            when(storageAdapter.getById(any(CollectionDefinition.class), eq(id)))
                .thenReturn(Optional.of(existingRecord));

            Map<String, Object> updateData = new HashMap<>(Map.of("price", -5.0));

            assertThrows(ValidationException.class, () ->
                engineWithHooks.update(testCollection, id, updateData));

            // Verify storage update was never called
            verify(storageAdapter, never()).update(any(CollectionDefinition.class), eq(id), any());
        }

        @Test
        @DisplayName("Should call after-create hook after successful create")
        void shouldCallAfterCreateHook() {
            AtomicBoolean afterHookCalled = new AtomicBoolean(false);
            hookRegistry.register(new BeforeSaveHook() {
                @Override
                public String getCollectionName() { return "products"; }
                @Override
                public void afterCreate(Map<String, Object> record, String tenantId) {
                    afterHookCalled.set(true);
                }
            });

            Map<String, Object> data = new HashMap<>(Map.of("name", "Widget", "price", 9.99));
            when(storageAdapter.create(any(CollectionDefinition.class), any())).thenAnswer(inv -> inv.getArgument(1));

            engineWithHooks.create(testCollection, data);
            assertTrue(afterHookCalled.get(), "After-create hook should have been called");
        }

        @Test
        @DisplayName("Should skip hooks when registry is null")
        void shouldSkipHooksWhenRegistryIsNull() {
            DefaultQueryEngine engineNoHooks = new DefaultQueryEngine(storageAdapter, hookValidationEngine);

            Map<String, Object> data = new HashMap<>(Map.of("name", "Widget", "price", 9.99));
            when(storageAdapter.create(any(CollectionDefinition.class), any())).thenAnswer(inv -> inv.getArgument(1));

            // Should not throw even without hook registry
            assertDoesNotThrow(() -> engineNoHooks.create(testCollection, data));
        }

        @Test
        @DisplayName("Should call after-delete hook after successful delete")
        void shouldCallAfterDeleteHook() {
            AtomicBoolean afterHookCalled = new AtomicBoolean(false);
            hookRegistry.register(new BeforeSaveHook() {
                @Override
                public String getCollectionName() { return "products"; }
                @Override
                public void afterDelete(String id, String tenantId) {
                    afterHookCalled.set(true);
                }
            });

            String id = UUID.randomUUID().toString();
            Map<String, Object> existingRecord = new HashMap<>(Map.of(
                "id", id, "name", "Widget", "price", 9.99));
            when(storageAdapter.getById(any(CollectionDefinition.class), eq(id)))
                .thenReturn(Optional.of(existingRecord));
            when(storageAdapter.delete(any(CollectionDefinition.class), eq(id)))
                .thenReturn(true);

            engineWithHooks.delete(testCollection, id);
            assertTrue(afterHookCalled.get(), "After-delete hook should have been called");
        }

        @Test
        @DisplayName("Should pass tenant from TenantContext to delete hooks, not the literal 'default'")
        void shouldPassContextTenantToDeleteHooks() {
            String contextTenant = UUID.randomUUID().toString();
            AtomicReference<String> beforeHookTenant = new AtomicReference<>();
            AtomicReference<String> afterHookTenant = new AtomicReference<>();
            hookRegistry.register(new BeforeSaveHook() {
                @Override
                public String getCollectionName() { return "products"; }
                @Override
                public BeforeSaveResult beforeDelete(String id, String tenantId) {
                    beforeHookTenant.set(tenantId);
                    return BeforeSaveResult.ok();
                }
                @Override
                public void afterDelete(String id, String tenantId) {
                    afterHookTenant.set(tenantId);
                }
            });

            String id = UUID.randomUUID().toString();
            // User-collection records carry no tenantId attribute — the engine
            // must fall back to TenantContext, never the literal "default".
            Map<String, Object> existingRecord = new HashMap<>(Map.of(
                "id", id, "name", "Widget", "price", 9.99));
            when(storageAdapter.getById(any(CollectionDefinition.class), eq(id)))
                .thenReturn(Optional.of(existingRecord));
            when(storageAdapter.delete(any(CollectionDefinition.class), eq(id)))
                .thenReturn(true);

            TenantContext.runWithTenant(contextTenant, () ->
                engineWithHooks.delete(testCollection, id));

            assertEquals(contextTenant, beforeHookTenant.get(),
                "Before-delete hook should receive the context tenant");
            assertEquals(contextTenant, afterHookTenant.get(),
                "After-delete hook should receive the context tenant");
        }
    }

    @Nested
    @DisplayName("Formula Field Evaluation Tests")
    class FormulaFieldTests {

        private FormulaEvaluator formulaEvaluator;
        private DefaultQueryEngine engineWithFormula;
        private CollectionDefinition orderCollection;

        @BeforeEach
        void setUp() {
            List<FormulaFunction> functions = List.of(
                    new BuiltInFunctions.If(),
                    new BuiltInFunctions.IsBlank(),
                    new BuiltInFunctions.BlankValue());
            formulaEvaluator = new FormulaEvaluator(functions);
            engineWithFormula = new DefaultQueryEngine(
                    storageAdapter, validationEngine, null, null,
                    formulaEvaluator, null, null);

            orderCollection = new CollectionDefinitionBuilder()
                    .name("orders")
                    .displayName("Orders")
                    .addField(new FieldDefinitionBuilder()
                            .name("amount").type(FieldType.DOUBLE).nullable(false).build())
                    .addField(new FieldDefinitionBuilder()
                            .name("quantity").type(FieldType.INTEGER).nullable(false).build())
                    .addField(new FieldDefinitionBuilder()
                            .name("total")
                            .type(FieldType.FORMULA)
                            .fieldTypeConfig(Map.of(
                                    "expression", "amount * quantity",
                                    "returnType", "DOUBLE"))
                            .build())
                    .build();
        }

        @Test
        @DisplayName("Should compute formula value on getById")
        void shouldComputeFormulaValueOnGetById() {
            String id = "order-1";
            Map<String, Object> stored = new HashMap<>();
            stored.put("id", id);
            stored.put("amount", 25.0);
            stored.put("quantity", 4);
            stored.put("total", null);

            when(storageAdapter.getById(orderCollection, id)).thenReturn(Optional.of(stored));

            Optional<Map<String, Object>> result = engineWithFormula.getById(orderCollection, id);

            assertTrue(result.isPresent());
            assertEquals(100.0, ((Number) result.get().get("total")).doubleValue(), 0.001);
        }

        @Test
        @DisplayName("Should return #ERROR when formula evaluation fails")
        void shouldReturnErrorWhenEvaluationFails() {
            String id = "order-2";
            Map<String, Object> stored = new HashMap<>();
            stored.put("id", id);
            stored.put("amount", 10.0);
            stored.put("quantity", 0);
            stored.put("total", null);

            CollectionDefinition divisionCollection = new CollectionDefinitionBuilder()
                    .name("orders_div")
                    .displayName("Orders Div")
                    .addField(new FieldDefinitionBuilder()
                            .name("amount").type(FieldType.DOUBLE).nullable(false).build())
                    .addField(new FieldDefinitionBuilder()
                            .name("quantity").type(FieldType.INTEGER).nullable(false).build())
                    .addField(new FieldDefinitionBuilder()
                            .name("total")
                            .type(FieldType.FORMULA)
                            .fieldTypeConfig(Map.of("expression", "amount / quantity"))
                            .build())
                    .build();

            when(storageAdapter.getById(divisionCollection, id)).thenReturn(Optional.of(stored));

            Optional<Map<String, Object>> result = engineWithFormula.getById(divisionCollection, id);

            assertTrue(result.isPresent());
            assertEquals("#ERROR", result.get().get("total"));
        }

        @Test
        @DisplayName("Should compute formula value on a sparse fields[] read that omits its (non-existent) column")
        void shouldComputeFormulaValueOnSparseFieldsetQuery() {
            // Mirrors what PhysicalTableStorageAdapter#buildSelectClause now returns for
            // fields[]=amount,quantity,total: "total" is a FORMULA field with no physical
            // column, so the storage layer's row never carries it. This is a QueryEngine-level
            // test, not a storage one — it pins that computeVirtualFields (unconditional over
            // definition.fields()) still fills the value in on the response regardless of what
            // the storage adapter returned, so the router-level GET keeps working end to end.
            QueryRequest request = new QueryRequest(
                    Pagination.defaults(), List.of(), List.of("amount", "quantity", "total"), List.of());

            Map<String, Object> stored = new HashMap<>();
            stored.put("id", "order-4");
            stored.put("amount", 25.0);
            stored.put("quantity", 4);

            when(storageAdapter.query(orderCollection, request))
                    .thenReturn(QueryResult.of(List.of(stored), 1, Pagination.defaults()));

            QueryResult result = engineWithFormula.executeQuery(orderCollection, request);

            assertEquals(1, result.data().size());
            assertEquals(100.0, ((Number) result.data().get(0).get("total")).doubleValue(), 0.001);
        }

        @Test
        @DisplayName("Should return null when formula expression is missing")
        void shouldReturnNullWhenExpressionMissing() {
            CollectionDefinition emptyConfigCollection = new CollectionDefinitionBuilder()
                    .name("orders_blank")
                    .displayName("Orders Blank")
                    .addField(new FieldDefinitionBuilder()
                            .name("amount").type(FieldType.DOUBLE).nullable(false).build())
                    .addField(new FieldDefinitionBuilder()
                            .name("total").type(FieldType.FORMULA).build())
                    .build();

            String id = "order-3";
            Map<String, Object> stored = new HashMap<>();
            stored.put("id", id);
            stored.put("amount", 50.0);
            stored.put("total", "previously-stored");

            when(storageAdapter.getById(emptyConfigCollection, id)).thenReturn(Optional.of(stored));

            Optional<Map<String, Object>> result = engineWithFormula.getById(emptyConfigCollection, id);

            assertTrue(result.isPresent());
            assertNull(result.get().get("total"));
        }
    }

    @Nested
    @DisplayName("Auto-number sequence naming")
    class AutoNumberSequenceNaming {

        @Test
        @DisplayName("kebab-case collection names map to valid Postgres identifiers")
        void kebabCaseCollectionName() {
            // Regression: seq_credit-notes_creditNoteNumber failed AutoNumberService's
            // identifier check and the record silently saved with a null number.
            assertEquals("seq_credit_notes_creditNoteNumber",
                    DefaultQueryEngine.autoNumberSequenceName("credit-notes", "creditNoteNumber"));
        }

        @Test
        @DisplayName("plain names are unchanged")
        void plainName() {
            assertEquals("seq_orders_orderNumber",
                    DefaultQueryEngine.autoNumberSequenceName("orders", "orderNumber"));
        }

        @Test
        @DisplayName("any non-identifier character is mapped to underscore")
        void otherSpecialCharacters() {
            assertEquals("seq_a_b_c_n_1",
                    DefaultQueryEngine.autoNumberSequenceName("a-b.c", "n 1"));
        }
    }

}
