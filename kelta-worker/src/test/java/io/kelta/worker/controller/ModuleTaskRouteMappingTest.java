package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.worker.module.ModuleTaskService;
import io.kelta.worker.module.ModuleTaskService.Heartbeat;
import io.kelta.worker.module.ModuleTaskService.Refusal;
import io.kelta.worker.module.ModuleTaskService.RefusedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/modules/self/tasks/complete} and {@code .../heartbeat} (four segments under
 * {@code /api}) reach {@link ModuleTaskController}, with {@code DynamicCollectionRouter}'s mappings
 * registered beside it (CLAUDE.md pitfall), with the raw body that carries the token, and each
 * refusal of {@link ModuleTaskService} reaches the module as its status and code, {@code no-store}.
 */
@DisplayName("Module task endpoints vs the generic collection router")
class ModuleTaskRouteMappingTest {

    private static final String TENANT = "tenant-1";
    private static final String TOKEN = "kmt_" + "D".repeat(43);
    private static final String COMPLETE = "/api/modules/self/tasks/complete";
    private static final String HEARTBEAT = "/api/modules/self/tasks/heartbeat";
    private static final String BODY = "{\"token\":\"" + TOKEN + "\",\"success\":true,\"output\":{\"rows\":42}}";
    private static final String BEAT = "{\"token\":\"" + TOKEN + "\"}";
    private static final byte[] BODY_BYTES = BODY.getBytes(StandardCharsets.UTF_8);
    private static final byte[] BEAT_BYTES = BEAT.getBytes(StandardCharsets.UTF_8);

    /** Stands in for {@code DynamicCollectionRouter}'s GET and POST mappings, shape for shape. */
    @RestController
    @RequestMapping("/api")
    static class RouterStub {
        @GetMapping("/{collectionName}")
        String one(@PathVariable String collectionName) {
            return "router";
        }

        @GetMapping("/{collectionName}/{id}")
        String two(@PathVariable String collectionName, @PathVariable String id) {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}")
        String three(@PathVariable String parentName, @PathVariable String parentId,
                     @PathVariable String childName) {
            return "router";
        }

        @GetMapping("/{parentName}/{parentId}/{childName}/{childId}")
        String four(@PathVariable String parentName, @PathVariable String parentId,
                    @PathVariable String childName, @PathVariable String childId) {
            return "router";
        }

        @PostMapping("/{collectionName}")
        String create(@PathVariable String collectionName) {
            return "router";
        }

        @PostMapping("/{parentName}/{parentId}/{childName}")
        String createChild(@PathVariable String parentName, @PathVariable String parentId,
                           @PathVariable String childName) {
            return "router";
        }
    }

    private ModuleTaskService service;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(ModuleTaskService.class);
        ObjectProvider<ModuleTaskService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        mvc = MockMvcBuilders.standaloneSetup(new ModuleTaskController(provider), new RouterStub()).build();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String callerUserId) throws Exception {
        if (callerUserId == null) {
            return mvc.perform(request.header("X-Tenant-ID", TENANT));
        }
        CallerContext caller = new CallerContext(callerUserId, CallerContext.UserType.INTERNAL, false, false);
        try {
            return CallerContext.callAs(caller, () -> {
                try {
                    return mvc.perform(request.header("X-Tenant-ID", TENANT));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            throw (Exception) e.getCause();
        }
    }

    private static MockHttpServletRequestBuilder complete() {
        return post(COMPLETE).contentType(MediaType.APPLICATION_JSON).content(BODY).header("X-Connected-App-Id", "app-1");
    }

    private static MockHttpServletRequestBuilder heartbeat() {
        return post(HEARTBEAT).contentType(MediaType.APPLICATION_JSON).content(BEAT).header("X-Connected-App-Id", "app-1");
    }

    @Test
    @DisplayName("a completion reaches ModuleTaskController with the raw body: 202, no body, no-store")
    void completionWinsOverTheRouter() throws Exception {
        perform(complete(), "svc-1")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(""));

        verify(service).complete(eq(TENANT), eq("app-1"), eq("svc-1"), eq(BODY_BYTES));
    }

    @Test
    @DisplayName("a heartbeat reaches ModuleTaskController: 200 with a fresh access token, no-store")
    void heartbeatWinsOverTheRouter() throws Exception {
        when(service.heartbeat(TENANT, "app-1", "svc-1", BEAT_BYTES)).thenReturn(new Heartbeat("fresh-token",
                Instant.parse("2026-10-31T12:15:00Z"), Instant.parse("2026-11-01T12:00:00Z")));

        perform(heartbeat(), "svc-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.accessToken").value("fresh-token"))
                .andExpect(jsonPath("$.accessTokenExpiresAt").value("2026-10-31T12:15:00Z"))
                .andExpect(jsonPath("$.taskExpiresAt").value("2026-11-01T12:00:00Z"));
    }

    @Test
    @DisplayName("a caller with no app id and no resolved user is handed to the service as such, and refused")
    void noIdentity() throws Exception {
        doThrow(new RefusedException(Refusal.NOT_MODULE_PRINCIPAL, "refused"))
                .when(service).complete(eq(TENANT), isNull(), isNull(), any());

        perform(post(COMPLETE).contentType(MediaType.APPLICATION_JSON).content(BODY), null)
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_MODULE_PRINCIPAL"));
    }

    @Test
    @DisplayName("each refusal maps to its status and code, on both endpoints")
    void refusals() throws Exception {
        Map<Refusal, Object[]> expected = Map.of(
                Refusal.NOT_MODULE_PRINCIPAL, new Object[] {403, "NOT_MODULE_PRINCIPAL"},
                Refusal.MODULE_NOT_ACTIVE, new Object[] {403, "MODULE_NOT_ACTIVE"},
                Refusal.INVALID_TASK_RESULT, new Object[] {400, "INVALID_TASK_RESULT"},
                Refusal.TASK_NOT_FOUND, new Object[] {404, "TASK_NOT_FOUND"},
                Refusal.TOKEN_UNAVAILABLE, new Object[] {503, "TOKEN_UNAVAILABLE"});
        for (Map.Entry<Refusal, Object[]> entry : expected.entrySet()) {
            doThrow(new RefusedException(entry.getKey(), "refused"))
                    .when(service).complete(eq(TENANT), eq("app-1"), eq("svc-1"), any());
            doThrow(new RefusedException(entry.getKey(), "refused"))
                    .when(service).heartbeat(eq(TENANT), eq("app-1"), eq("svc-1"), any());

            perform(complete(), "svc-1")
                    .andExpect(status().is((Integer) entry.getValue()[0]))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(jsonPath("$.errors[0].code").value(entry.getValue()[1]));
            perform(heartbeat(), "svc-1")
                    .andExpect(status().is((Integer) entry.getValue()[0]))
                    .andExpect(jsonPath("$.errors[0].code").value(entry.getValue()[1]));
        }
    }

    @Test
    @DisplayName("an answer never echoes the token")
    void tokenNotEchoed() throws Exception {
        doThrow(new RefusedException(Refusal.TASK_NOT_FOUND, "No pending task of this module has that token"))
                .when(service).complete(eq(TENANT), eq("app-1"), eq("svc-1"), any());

        perform(complete(), "svc-1")
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(TOKEN))));
    }

    @Test
    @DisplayName("without module support every caller is refused, never a 404")
    @SuppressWarnings("unchecked")
    void moduleSupportOff() throws Exception {
        ObjectProvider<ModuleTaskService> absent = mock(ObjectProvider.class);
        MockMvc off = MockMvcBuilders.standaloneSetup(new ModuleTaskController(absent), new RouterStub()).build();

        off.perform(post(COMPLETE).header("X-Tenant-ID", TENANT).header("X-Connected-App-Id", "app-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_MODULE_PRINCIPAL"));
        off.perform(post(HEARTBEAT).header("X-Tenant-ID", TENANT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a token in the path is no task endpoint: refused before the controller")
    void tokenInPathIsNotMapped() throws Exception {
        perform(post("/api/modules/self/tasks/" + TOKEN).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("X-Connected-App-Id", "app-1"), "svc-1")
                .andExpect(status().is4xxClientError());
        perform(post("/api/modules/self/tasks/" + TOKEN + "/heartbeat").header("X-Connected-App-Id", "app-1"),
                "svc-1")
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }
}
