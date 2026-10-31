package io.kelta.runtime.query;

import io.kelta.runtime.context.ActingUser;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.events.RecordEventPublisher;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.storage.StorageAdapter;
import io.kelta.runtime.validation.ValidationEngine;
import io.kelta.runtime.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The user a record-changed event names is the user doing that write — never the record's stored
 * creator or last updater. Every record-triggered flow starts as this user
 * ({@code flow_execution.started_by}, the {@code act} claim sent to external modules), so an
 * update or delete by B on a record A created must publish as B.
 */
@DisplayName("DefaultQueryEngine — the user a record-changed event names")
class DefaultQueryEngineEventUserTest {

    private static final String USER_A = "aaaaaaaa-0000-4000-8000-00000000000a";
    private static final String USER_B = "bbbbbbbb-0000-4000-8000-00000000000b";
    private static final String STAFF = "cccccccc-0000-4000-8000-00000000000c";
    private static final String RECORD_ID = "11111111-0000-4000-8000-000000000001";

    private StorageAdapter storageAdapter;
    private RecordEventPublisher publisher;
    private DefaultQueryEngine engine;
    private CollectionDefinition products;

    @BeforeEach
    void setUp() {
        storageAdapter = mock(StorageAdapter.class);
        publisher = mock(RecordEventPublisher.class);
        ValidationEngine validationEngine = mock(ValidationEngine.class);
        when(validationEngine.validate(any(), any(), any())).thenReturn(ValidationResult.success());
        when(validationEngine.validate(any(), any(), any(), any())).thenReturn(ValidationResult.success());
        engine = new DefaultQueryEngine(storageAdapter, validationEngine, null, null, null, null, null,
                publisher, null);

        products = new CollectionDefinitionBuilder()
                .name("products").displayName("Products")
                .addField(FieldDefinition.string("name"))
                .build();

        // A's record: A created it and was the last to touch it.
        Map<String, Object> stored = new HashMap<>(Map.of(
                "id", RECORD_ID, "name", "Widget", "createdBy", USER_A, "updatedBy", USER_A));
        when(storageAdapter.getById(products, RECORD_ID)).thenReturn(Optional.of(stored));
        when(storageAdapter.create(any(), anyMap()))
                .thenAnswer(inv -> new HashMap<>(inv.<Map<String, Object>>getArgument(1)));
        when(storageAdapter.update(eq(products), eq(RECORD_ID), anyMap()))
                .thenAnswer(inv -> {
                    Map<String, Object> merged = new HashMap<>(stored);
                    merged.putAll(inv.<Map<String, Object>>getArgument(2));
                    return Optional.of(merged);
                });
        when(storageAdapter.delete(products, RECORD_ID)).thenReturn(true);
    }

    private static CallerContext caller(String userId) {
        return new CallerContext(userId, UserType.INTERNAL, false, false);
    }

    private String publishedUser() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<PlatformEvent<RecordChangedPayload>> captor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue().getUserId();
    }

    private Map<String, Object> patchBy(String updatedBy) {
        Map<String, Object> patch = new HashMap<>(Map.of("name", "Renamed"));
        if (updatedBy != null) {
            patch.put("updatedBy", updatedBy);
        }
        return patch;
    }

    @Nested
    @DisplayName("under a request caller")
    class RequestCaller {

        @Test
        @DisplayName("an update by B on A's record publishes as B")
        void updateBySecondUserPublishesAsThem() {
            CallerContext.runAs(caller(USER_B), () -> engine.update(products, RECORD_ID, patchBy(USER_B)));

            assertThat(publishedUser()).isEqualTo(USER_B);
        }

        @Test
        @DisplayName("a delete by B of A's record publishes as B")
        void deleteBySecondUserPublishesAsThem() {
            CallerContext.runAs(caller(USER_B), () -> engine.delete(products, RECORD_ID));

            assertThat(publishedUser()).isEqualTo(USER_B);
        }

        @Test
        @DisplayName("a create publishes as the caller, whatever createdBy the data carries")
        void createPublishesAsCaller() {
            CallerContext.runAs(caller(USER_B), () ->
                    engine.create(products, new HashMap<>(Map.of("name", "New", "createdBy", USER_A))));

            assertThat(publishedUser()).isEqualTo(USER_B);
        }
    }

    @Nested
    @DisplayName("under a bound acting user (a flow step's actor, an on-behalf write)")
    class BoundActingUser {

        @Test
        @DisplayName("an update with no caller publishes as the acting user, not the record's creator")
        void flowUpdatePublishesAsActor() {
            ActingUser.runAs(USER_B, () -> engine.update(products, RECORD_ID, patchBy(null)));

            assertThat(publishedUser()).isEqualTo(USER_B);
        }

        @Test
        @DisplayName("a delete with no caller publishes as the acting user")
        void flowDeletePublishesAsActor() {
            ActingUser.runAs(USER_B, () -> engine.delete(products, RECORD_ID));

            assertThat(publishedUser()).isEqualTo(USER_B);
        }

        @Test
        @DisplayName("the acting user wins over a re-bound caller (support staff writing as a member)")
        void actingUserWinsOverReboundCaller() {
            ActingUser.runAs(STAFF, () ->
                    CallerContext.runAs(caller(USER_A), () -> engine.update(products, RECORD_ID, patchBy(null))));

            assertThat(publishedUser()).isEqualTo(STAFF);
        }
    }

    @Nested
    @DisplayName("with nobody bound (a platform writer): never a value the data carries")
    class NobodyBound {

        @Test
        @DisplayName("an update publishes as system, whatever updatedBy the patch carries")
        void stampedUpdatePublishesAsSystem() {
            engine.update(products, RECORD_ID, patchBy(USER_B));

            assertThat(publishedUser()).isEqualTo("system");
        }

        @Test
        @DisplayName("an unstamped update publishes as system — never the stored creator or updater")
        void unstampedUpdatePublishesAsSystem() {
            engine.update(products, RECORD_ID, patchBy(null));

            assertThat(publishedUser()).isEqualTo("system");
        }

        @Test
        @DisplayName("a delete publishes as system — never the stored creator or updater")
        void deletePublishesAsSystem() {
            engine.delete(products, RECORD_ID);

            assertThat(publishedUser()).isEqualTo("system");
        }

        @Test
        @DisplayName("a create publishes as system, whatever createdBy the data carries")
        void stampedCreatePublishesAsSystem() {
            engine.create(products, new HashMap<>(Map.of("name", "New", "createdBy", USER_A)));

            assertThat(publishedUser()).isEqualTo("system");
        }
    }
}
