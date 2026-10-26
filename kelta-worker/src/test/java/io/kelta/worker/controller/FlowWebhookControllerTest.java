package io.kelta.worker.controller;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.worker.repository.FlowRepository;
import io.kelta.worker.service.FlowActorResolver;
import io.kelta.worker.service.FlowWebhookPolicy;
import io.kelta.worker.service.TenantSlugResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/webhooks/{flowId}} through the real MVC dispatch, so method routing, content-type
 * handling and the servlet's form parsing are what production sees. The engine is mocked; each
 * test reads the initial state the controller handed it.
 */
@DisplayName("FlowWebhookController")
class FlowWebhookControllerTest {

    private static final String FLOW_ID = "flow-1";
    private static final String URL = "/api/webhooks/" + FLOW_ID;
    private static final String SECRET = "s3cret";

    private FlowEngine flowEngine;
    private FlowRepository flowRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        flowEngine = mock(FlowEngine.class);
        flowRepository = mock(FlowRepository.class);
        TenantSlugResolver slugResolver = mock(TenantSlugResolver.class);
        when(slugResolver.resolveSlug("tenant-1")).thenReturn(Optional.of("acme"));
        FlowActorResolver actorResolver = mock(FlowActorResolver.class);
        when(actorResolver.resolve(eq("tenant-1"), eq(FLOW_ID), isNull())).thenReturn("user-1");
        when(flowEngine.startExecution(anyString(), anyString(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn("exec-1");

        FlowWebhookController controller = new FlowWebhookController(flowEngine, flowRepository,
                new InitialStateBuilder(), new ObjectMapper(), slugResolver, actorResolver, "https://kelta.test");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void flow(String triggerConfig) {
        Map<String, Object> row = new HashMap<>();
        row.put("flow_type", "AUTOLAUNCHED");
        row.put("active", true);
        row.put("definition", "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}");
        row.put("tenant_id", "tenant-1");
        row.put("trigger_config", triggerConfig);
        when(flowRepository.findFlowById(FLOW_ID)).thenReturn(Optional.of(row));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> startedState() {
        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass(Map.class);
        verify(flowEngine).startExecution(eq("tenant-1"), eq(FLOW_ID), anyString(), state.capture(),
                eq("user-1"), isNull(), eq(false));
        return state.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> state, String key) {
        return (Map<String, Object>) state.get(key);
    }

    /** A raw URI, so the percent-encoding reaches the servlet as a client sent it (a template would re-encode it). */
    private static URI uri(String query) {
        return URI.create(URL + query);
    }

    private void verifyNoRun() {
        verify(flowEngine, never()).startExecution(anyString(), anyString(), anyString(), any(), any(), any(), anyBoolean());
    }

    // --- no triggerConfig.webhook: the original contract --------------------------------------

    @Test
    @DisplayName("Without a webhook block a JSON POST starts the flow with the body in $.input and a flow-executions reply")
    void defaultJsonPost() throws Exception {
        flow(null);

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).header("User-Agent", "Stripe/1.0")
                        .content("{\"orderId\":\"ord-123\",\"amount\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("flow-executions"))
                .andExpect(jsonPath("$.data.id").value("exec-1"))
                .andExpect(jsonPath("$.data.attributes.flowId").value(FLOW_ID))
                .andExpect(jsonPath("$.data.attributes.status").value("RUNNING"));

        Map<String, Object> state = startedState();
        assertThat(section(state, "trigger")).containsEntry("type", "WEBHOOK");
        assertThat(section(state, "input")).containsEntry("orderId", "ord-123").containsEntry("amount", 42);
        assertThat(section(state, "headers"))
                .isEqualTo(Map.of("content-type", "application/json", "user-agent", "Stripe/1.0"));
        assertThat(section(state, "query")).isEmpty();
    }

    @Test
    @DisplayName("Without a webhook block an empty POST starts the flow with an empty $.input")
    void defaultEmptyPost() throws Exception {
        flow("{}");

        mvc.perform(post(URL)).andExpect(status().isOk());

        assertThat(section(startedState(), "input")).isEmpty();
    }

    @Test
    @DisplayName("Without a webhook block a GET answers 405 and starts no run")
    void defaultGetIs405() throws Exception {
        flow(null);

        mvc.perform(get(URL + "?a=1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));

        verifyNoRun();
    }

    @Test
    @DisplayName("A form POST to a JSON-only flow answers 415")
    void formToJsonOnlyFlowIs415() throws Exception {
        flow(null);

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoRun();
    }

    @Test
    @DisplayName("Unknown, non-AUTOLAUNCHED and inactive flows keep their 404/400 ahead of the policy checks")
    void flowChecksComeFirst() throws Exception {
        when(flowRepository.findFlowById("missing")).thenReturn(Optional.empty());
        mvc.perform(get("/api/webhooks/missing")).andExpect(status().isNotFound());

        Map<String, Object> inactive = new HashMap<>(Map.of("flow_type", "AUTOLAUNCHED", "active", false,
                "trigger_config", "{\"webhook\":{\"methods\":[\"GET\"]}}"));
        when(flowRepository.findFlowById(FLOW_ID)).thenReturn(Optional.of(inactive));
        mvc.perform(get(URL)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].detail").value("Flow is not active"));

        verifyNoRun();
    }

    // --- GET -----------------------------------------------------------------------------------

    @Test
    @DisplayName("A flow allowing GET starts on GET with the query in $.query (sig excluded)")
    void getStartsWithQuery() throws Exception {
        flow("{\"webhook\":{\"methods\":[\"GET\"],\"secret\":\"" + SECRET + "\"}}");

        mvc.perform(get(URL + "?a=1&tag=x&tag=y").header(FlowWebhookPolicy.SECRET_HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("flow-executions"));

        Map<String, Object> query = section(startedState(), "query");
        assertThat(query).containsEntry("a", "1").containsEntry("tag", java.util.List.of("x", "y"));
    }

    @Test
    @DisplayName("A flow allowing only GET answers 405 to POST")
    void getOnlyFlowRefusesPost() throws Exception {
        flow("{\"webhook\":{\"methods\":[\"GET\"],\"secret\":\"" + SECRET + "\"}}");

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(FlowWebhookPolicy.SECRET_HEADER, SECRET))
                .andExpect(status().isMethodNotAllowed());

        verifyNoRun();
    }

    // --- form bodies ---------------------------------------------------------------------------

    @Test
    @DisplayName("A flow allowing form bodies starts on a form POST with the fields in $.input")
    void formPostStarts() throws Exception {
        flow("{\"webhook\":{\"bodies\":[\"form\"],\"secret\":\"" + SECRET + "\"}}");

        mvc.perform(post(URL + "?u=42").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(FlowWebhookPolicy.SECRET_HEADER, SECRET)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk());

        Map<String, Object> state = startedState();
        assertThat(section(state, "input")).isEqualTo(Map.of("List-Unsubscribe", "One-Click"));
        assertThat(section(state, "query")).isEqualTo(Map.of("u", "42"));
    }

    @Test
    @DisplayName("A form-only flow answers 415 to a JSON body")
    void formOnlyFlowRefusesJson() throws Exception {
        flow("{\"webhook\":{\"bodies\":[\"form\"],\"secret\":\"" + SECRET + "\"}}");

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}")
                        .header(FlowWebhookPolicy.SECRET_HEADER, SECRET))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoRun();
    }

    // --- secret requirement --------------------------------------------------------------------

    @Test
    @DisplayName("GET without a configured secret is an invalid config: 403 with a clear error, no run")
    void getWithoutSecretIs403() throws Exception {
        flow("{\"webhook\":{\"methods\":[\"GET\",\"POST\"]}}");

        mvc.perform(get(URL + "?a=1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail").value(
                        "Webhook configuration is invalid: GET and form bodies require triggerConfig.webhook.secret to be set"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        verifyNoRun();
    }

    @Test
    @DisplayName("Form bodies without a configured secret are an invalid config: 403, no run")
    void formWithoutSecretIs403() throws Exception {
        flow("{\"webhook\":{\"bodies\":[\"json\",\"form\"]}}");

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].detail").value(
                        "Webhook configuration is invalid: GET and form bodies require triggerConfig.webhook.secret to be set"));

        verifyNoRun();
    }

    // --- authentication ------------------------------------------------------------------------

    @Test
    @DisplayName("With a secret, a JSON POST carrying the right header starts the flow")
    void headerSecretStarts() throws Exception {
        flow("{\"webhook\":{\"secret\":\"" + SECRET + "\"}}");

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}")
                        .header(FlowWebhookPolicy.SECRET_HEADER, SECRET))
                .andExpect(status().isOk());

        assertThat(section(startedState(), "input")).containsEntry("a", 1);
    }

    @Test
    @DisplayName("With a secret, a missing or wrong header answers 401 and starts no run")
    void missingOrWrongSecretIs401() throws Exception {
        flow("{\"webhook\":{\"secret\":\"" + SECRET + "\"}}");

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(FlowWebhookPolicy.SECRET_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());

        verifyNoRun();
    }

    @Test
    @DisplayName("A valid sig over the sorted remaining query starts a GET flow; sig stays out of $.query")
    void validSignatureStarts() throws Exception {
        flow("{\"webhook\":{\"methods\":[\"GET\"],\"secret\":\"" + SECRET + "\"}}");
        String sig = FlowWebhookPolicy.sign(SECRET, "email=a@b.com&token=t1");

        mvc.perform(get(uri("?token=t1&email=a%40b.com&sig=" + sig)))
                .andExpect(status().isOk());

        assertThat(section(startedState(), "query"))
                .isEqualTo(Map.of("token", "t1", "email", "a@b.com"));
    }

    @Test
    @DisplayName("A wrong sig, or a query changed after signing, answers 401 and starts no run")
    void invalidSignatureIs401() throws Exception {
        flow("{\"webhook\":{\"methods\":[\"GET\"],\"secret\":\"" + SECRET + "\"}}");
        String sig = FlowWebhookPolicy.sign(SECRET, "email=a@b.com&token=t1");

        mvc.perform(get(uri("?token=t2&email=a%40b.com&sig=" + sig))).andExpect(status().isUnauthorized());
        mvc.perform(get(uri("?token=t1&email=a%40b.com&sig=" + FlowWebhookPolicy.sign("other", "email=a@b.com&token=t1"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(uri("?token=t1&email=a%40b.com"))).andExpect(status().isUnauthorized());

        verifyNoRun();
    }

    // --- custom response -----------------------------------------------------------------------

    @Test
    @DisplayName("response.status/body/contentType replace the default reply")
    void customContentResponse() throws Exception {
        flow("{\"webhook\":{\"bodies\":[\"form\"],\"secret\":\"" + SECRET + "\","
                + "\"response\":{\"status\":202,\"body\":\"Unsubscribed\",\"contentType\":\"text/plain\"}}}");

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(FlowWebhookPolicy.SECRET_HEADER, SECRET)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"))
                .andExpect(content().string("Unsubscribed"));
    }

    @Test
    @DisplayName("response.redirect answers 303 with Location")
    void redirectResponse() throws Exception {
        flow("{\"webhook\":{\"methods\":[\"GET\"],\"secret\":\"" + SECRET + "\","
                + "\"response\":{\"redirect\":\"https://example.com/unsubscribed\"}}}");

        mvc.perform(get(URL + "?u=42").header(FlowWebhookPolicy.SECRET_HEADER, SECRET))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://example.com/unsubscribed"));

        startedState();
    }

    @Test
    @DisplayName("An invalid response config falls back to the default JSON:API reply")
    void invalidResponseFallsBack() throws Exception {
        flow("{\"webhook\":{\"secret\":\"" + SECRET + "\",\"response\":{\"redirect\":\"javascript:alert(1)\"}}}");

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(FlowWebhookPolicy.SECRET_HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("flow-executions"))
                .andExpect(jsonPath("$.data.id").value("exec-1"));
    }
}
