package io.kelta.worker.module;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.CollectionDefinitionBuilder;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.ModuleStore;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import io.kelta.runtime.workflow.HookConflictException;
import io.kelta.runtime.workflow.ModuleTimeoutException;
import io.kelta.runtime.workflow.ModuleUnavailableException;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.kelta.worker.module.ExternalModuleFixtures.json;
import static io.kelta.worker.module.ExternalModuleFixtures.manifest;
import static io.kelta.worker.module.ExternalModuleFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RemoteBeforeSaveHook")
class RemoteBeforeSaveHookTest {

    private static final String COLLECTION = "orders";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleHttpClient http = mock(ModuleHttpClient.class);
    private final ExternalModuleRegistry registry = mock(ExternalModuleRegistry.class);
    private final CollectionRegistry collections = mock(CollectionRegistry.class);
    private ExternalModuleInvoker invoker;

    /** The principal: edits orders, sees {@code cost} masked and never sees {@code secret}. */
    private final ModulePrincipalAccess access = new ModulePrincipalAccess(
            Map.of(COLLECTION, new ModulePrincipalAccess.ObjectAccess(false, true, true, false)),
            Map.of(COLLECTION, Map.of("internalNote", "HIDDEN", "cost", "MASKED")), false, false);

    @BeforeEach
    void setUp() {
        ModuleAccessTokenService tokens = mock(ModuleAccessTokenService.class);
        when(tokens.accessToken(any())).thenReturn("token");
        invoker = new ExternalModuleInvoker(mock(ModuleStore.class), http, tokens, mock(TenantSlugResolver.class),
                null, new ExternalModuleFixtures.MutableClock(), Runnable::run);
        CollectionDefinition orders = new CollectionDefinitionBuilder().name(COLLECTION).displayName("Orders")
                .addField(FieldDefinition.string("status"))
                .addField(FieldDefinition.string("total"))
                .addField(FieldDefinition.string("internalNote"))
                .addField(FieldDefinition.string("cost"))
                .addField(FieldDefinition.string("secret").withServerOnly(true))
                .addField(FieldDefinition.string("ledgerState").withSystemMaintained(true))
                .build();
        when(collections.get(COLLECTION)).thenReturn(orders);
        when(registry.endpoint(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE)).thenReturn(Optional.of(
                new ExternalModuleRegistry.Endpoint(row(TenantModuleData.STATUS_ACTIVE), manifest(), access)));
    }

    private RemoteBeforeSaveHook hook(String failurePolicy, String... events) {
        return new RemoteBeforeSaveHook(ExternalModuleFixtures.TENANT, ExternalModuleFixtures.MODULE,
                new ExternalModuleManifest.Hook(COLLECTION, List.of(events), 1000, 2000, failurePolicy), registry,
                invoker, collections, null, mapper);
    }

    private void answer(String body) {
        when(http.invoke(any(), any(), any(), any())).thenReturn(json(200, body));
    }

    private JsonNode sentBody() throws Exception {
        ArgumentCaptor<ModuleHttpClient.Call> sent = ArgumentCaptor.captor();
        verify(http).invoke(any(), any(), sent.capture(), any());
        assertThat(sent.getValue().path()).isEqualTo("/kelta/v1/hooks");
        assertThat(sent.getValue().timeout()).isEqualTo(Duration.ofMillis(2000));
        return mapper.readTree(sent.getValue().body());
    }

    @Test
    @DisplayName("sends the record filtered for the principal and applies allowed field updates")
    void allowedWithFieldUpdates() throws Exception {
        answer("{\"allowed\":true,\"fieldUpdates\":{\"status\":\"REVIEW\"}}");
        Map<String, Object> record = new HashMap<>(Map.of("status", "NEW", "total", "10", "internalNote", "x",
                "cost", "1234", "secret", "s3cr3t"));
        Map<String, Object> previous = Map.of("status", "DRAFT", "total", "10");

        BeforeSaveResult result = hook("fail", "beforeUpdate").beforeUpdate(COLLECTION, "rec-1", record, previous,
                ExternalModuleFixtures.TENANT);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFieldUpdates()).containsExactly(Map.entry("status", "REVIEW"));
        JsonNode body = sentBody();
        assertThat(body.get("operation").stringValue()).isEqualTo("update");
        assertThat(body.get("recordId").stringValue()).isEqualTo("rec-1");
        JsonNode sent = body.get("record");
        assertThat(sent.has("secret")).as("server-only").isFalse();
        assertThat(sent.has("internalNote")).as("HIDDEN").isFalse();
        assertThat(sent.get("cost").stringValue()).as("MASKED").isEqualTo("******");
        assertThat(sent.get("status").stringValue()).isEqualTo("NEW");
        List<String> changed = mapper.convertValue(body.get("changedFields"), List.class);
        assertThat(changed).contains("status").doesNotContain("total");
    }

    @Test
    @DisplayName("allowed:false with errors is a validation failure, one error per entry")
    void deniedWithErrors() {
        answer("{\"allowed\":false,\"errors\":[{\"field\":\"total\",\"message\":\"too high\",\"code\":\"LIMIT\"}]}");

        BeforeSaveResult result = hook("fail", "beforeCreate").beforeCreate(COLLECTION,
                new HashMap<>(Map.of("total", "999")), ExternalModuleFixtures.TENANT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).containsExactly(new BeforeSaveResult.ValidationError("total", "too high", "LIMIT"));
    }

    @Test
    @DisplayName("allowed:false with no errors still rejects, with one record-level error")
    void deniedWithoutErrors() {
        answer("{\"allowed\":false}");

        BeforeSaveResult result = hook("fail", "beforeCreate").beforeCreate(COLLECTION, new HashMap<>(),
                ExternalModuleFixtures.TENANT);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).singleElement().extracting(BeforeSaveResult.ValidationError::field).isNull();
    }

    @Test
    @DisplayName("a conflict is HookConflictException (409), with the module's code")
    void conflict() {
        answer("{\"allowed\":false,\"conflict\":{\"field\":\"status\",\"message\":\"already shipped\","
                + "\"code\":\"ORDER_SHIPPED\"}}");

        assertThatThrownBy(() -> hook("fail", "beforeUpdate").beforeUpdate(COLLECTION, "rec-1", new HashMap<>(),
                Map.of(), ExternalModuleFixtures.TENANT))
                .isInstanceOf(HookConflictException.class)
                .satisfies(e -> {
                    HookConflictException conflict = (HookConflictException) e;
                    assertThat(conflict.getCode()).isEqualTo("ORDER_SHIPPED");
                    assertThat(conflict.getFieldName()).isEqualTo("status");
                });
    }

    @Test
    @DisplayName("an update to a field the principal may not write is a module error: refused under fail, dropped under ignore")
    void unwritableFieldUpdate() {
        answer("{\"allowed\":true,\"fieldUpdates\":{\"cost\":\"0\"}}");

        assertThatThrownBy(() -> hook("fail", "beforeUpdate").beforeUpdate(COLLECTION, "rec-1", new HashMap<>(),
                Map.of(), ExternalModuleFixtures.TENANT))
                .isExactlyInstanceOf(ModuleUnavailableException.class).hasMessageContaining("[cost]");

        BeforeSaveResult ignored = hook("ignore", "beforeUpdate").beforeUpdate(COLLECTION, "rec-1", new HashMap<>(),
                Map.of(), ExternalModuleFixtures.TENANT);
        assertThat(ignored.isSuccess()).isTrue();
        assertThat(ignored.hasFieldUpdates()).as("never applied").isFalse();
    }

    @Test
    @DisplayName("system-maintained, audit and undeclared fields are never writable by a module")
    void platformOwnedFieldsAreUnwritable() {
        for (String field : List.of("ledgerState", "createdBy", "id", "noSuchField")) {
            reset(http);
            answer("{\"allowed\":true,\"fieldUpdates\":{\"" + field + "\":\"x\"}}");

            assertThatThrownBy(() -> hook("fail", "beforeUpdate").beforeUpdate(COLLECTION, "rec-1",
                    new HashMap<>(), Map.of(), ExternalModuleFixtures.TENANT))
                    .as(field).isExactlyInstanceOf(ModuleUnavailableException.class)
                    .hasMessageContaining("[" + field + "]");
        }
    }

    @Test
    @DisplayName("a create needs the create grant: the principal may edit orders but not create them")
    void createNeedsCreateGrant() {
        answer("{\"allowed\":true,\"fieldUpdates\":{\"status\":\"NEW\"}}");

        assertThatThrownBy(() -> hook("fail", "beforeCreate").beforeCreate(COLLECTION, new HashMap<>(),
                ExternalModuleFixtures.TENANT)).isInstanceOf(ModuleUnavailableException.class);
    }

    @Test
    @DisplayName("a timeout fails the write under failurePolicy fail and lets it through under ignore")
    void timeoutFollowsFailurePolicy() {
        when(http.invoke(any(), any(), any(), any()))
                .thenThrow(new ModuleHttpClient.ModuleCallException(0, "timed out", true));

        assertThatThrownBy(() -> hook("fail", "beforeCreate").beforeCreate(COLLECTION, new HashMap<>(),
                ExternalModuleFixtures.TENANT)).isInstanceOf(ModuleTimeoutException.class);
        assertThat(hook("ignore", "beforeCreate").beforeCreate(COLLECTION, new HashMap<>(),
                ExternalModuleFixtures.TENANT).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("delete sends only the id and honors only allowed")
    void deleteHonorsOnlyAllowed() throws Exception {
        answer("{\"allowed\":true,\"fieldUpdates\":{\"status\":\"GONE\"}}");

        BeforeSaveResult result = hook("fail", "beforeDelete").beforeDelete(COLLECTION, "rec-1",
                ExternalModuleFixtures.TENANT);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasFieldUpdates()).isFalse();
        JsonNode body = sentBody();
        assertThat(body.get("operation").stringValue()).isEqualTo("delete");
        assertThat(body.has("record")).isFalse();
    }

    @Test
    @DisplayName("an event the registration does not list is not sent")
    void unlistedEventIsSkipped() {
        assertThat(hook("fail", "beforeCreate").beforeDelete(COLLECTION, "rec-1", ExternalModuleFixtures.TENANT)
                .isSuccess()).isTrue();
        verify(http, never()).invoke(any(), any(), any(), any());
    }

    @Test
    @DisplayName("registers under its manifest collection and order")
    void collectionAndOrder() {
        RemoteBeforeSaveHook hook = hook("fail", "beforeCreate");
        assertThat(hook.getCollectionName()).isEqualTo(COLLECTION);
        assertThat(hook.getOrder()).isEqualTo(1000);
    }
}
