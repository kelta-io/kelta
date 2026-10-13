package io.kelta.runtime.module.integration.handlers;

import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.FlowStore;
import io.kelta.runtime.flow.StateDataResolver;
import io.kelta.runtime.module.integration.mapping.PayloadMapperService;
import io.kelta.runtime.module.integration.spi.ApiSpecStore;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.module.integration.spi.IdempotencyStore;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Runs a real {@link FlowEngine} with a {@code CALL_API} step against a loopback server and
 * checks that nothing the engine hands to the {@link FlowStore} — step-log input and output
 * snapshots, execution state, the final error — carries the injected credential.
 */
@DisplayName("CALL_API in a flow")
class CallApiFlowRedactionTest {

    private static final String KEY = "ingest_key_5f3a9c";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LoopbackServer server;
    private FlowStore flowStore;
    private FlowEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        server = new LoopbackServer();
        flowStore = mock(FlowStore.class);
        when(flowStore.createExecution(any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn("exec-1");
        when(flowStore.logStepExecution(any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), anyInt())).thenReturn("step-1");

        CredentialResolverPort credentials = mock(CredentialResolverPort.class);
        when(credentials.resolve(eq("t-1"), eq("ingest"), anyString()))
            .thenReturn(new ResolvedCredential("c-1", "ingest", "api_key",
                Map.of("value", KEY), Map.of("headerName", "api_key", "location", "query"),
                Instant.now()));

        ActionHandlerRegistry registry = new ActionHandlerRegistry();
        registry.register(new CallApiActionHandler(objectMapper,
            new PayloadMapperService(new StateDataResolver(objectMapper)),
            mock(ApiSpecStore.class), credentials, mock(IdempotencyStore.class),
            new OutboundUrlGuard("127.0.0.0/8")));
        engine = new FlowEngine(flowStore, registry, objectMapper, 1);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
        server.close();
    }

    private String flow() {
        return """
            {
              "StartAt": "Fetch",
              "States": {
                "Fetch": {
                  "Type": "Task",
                  "Resource": "CALL_API",
                  "Parameters": {
                    "mode": "raw",
                    "url": "%s/feed",
                    "queryParams": { "page": "1" },
                    "credentialRef": "ingest"
                  },
                  "ResultPath": "$.fetch",
                  "Next": "Done"
                },
                "Done": { "Type": "Pass", "End": true }
              }
            }
            """.formatted(server.base());
    }

    @Test
    @DisplayName("A successful run persists no credential in step logs or the execution row")
    void successfulRunPersistsNoKey() throws Exception {
        server.responseBody = "{\"next\":\"/feed?page=2&api_key=" + KEY + "\"}";

        Map<String, Object> result = engine.executeSynchronous("t-1", "f-1", flow(), Map.of(), "u-1");

        assertEquals("/feed?page=1&api_key=" + KEY, server.lastPath, "the upstream still gets the key");
        @SuppressWarnings("unchecked")
        Map<String, Object> fetch = (Map<String, Object>) result.get("fetch");
        assertEquals(server.base() + "/feed?page=1&api_key=***", fetch.get("url"));

        ArgumentCaptor<Map<String, Object>> inputs = mapCaptor();
        verify(flowStore, times(2)).logStepExecution(eq("exec-1"), any(), any(), any(),
            inputs.capture(), any(), any(), any(), any(), any(), anyInt());
        assertTrue(objectMapper.writeValueAsString(inputs.getAllValues().get(1)).contains("api_key=***"),
            "the next step's input snapshot carries the masked url");
        assertStoreNeverSawKey();
    }

    @Test
    @DisplayName("A failed run persists no credential in its error or snapshots")
    void failedRunPersistsNoKey() throws Exception {
        server.status = 401;
        server.responseBody = "{\"error\":\"bad key " + KEY + "\"}";

        engine.executeSynchronous("t-1", "f-1", flow(), Map.of(), "u-1");

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(flowStore).completeExecution(eq("exec-1"), eq("FAILED"), any(), error.capture(),
            anyInt(), anyInt());
        assertTrue(error.getValue().contains("Api.HttpClientError: 401"), error.getValue());
        assertStoreNeverSawKey();
    }

    /** Every argument of every call the engine made on the store, serialized. */
    private void assertStoreNeverSawKey() throws Exception {
        List<Object> written = mockingDetails(flowStore).getInvocations().stream()
            .<Object>map(invocation -> Arrays.asList(invocation.getArguments()))
            .toList();
        assertFalse(written.isEmpty());
        String serialized = objectMapper.writeValueAsString(written);
        assertFalse(serialized.contains(KEY), serialized);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
