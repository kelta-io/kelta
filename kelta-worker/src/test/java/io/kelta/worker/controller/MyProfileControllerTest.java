package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.system.SystemCollectionDefinitions;
import io.kelta.runtime.query.QueryEngine;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.router.GlobalExceptionHandler;
import io.kelta.worker.service.SelfProfileWriteContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET|PATCH /api/me/profile} — the caller's own {@code users} row, fixed allow-list.
 *
 * <p>The router stand-in is registered deliberately: {@code DynamicCollectionRouter} maps
 * {@code /api/{collection}/{id}} for GET and PATCH, which {@code /api/me/profile} also matches —
 * the literal mapping must win (see {@code concerns.md} → Fragile Areas).
 */
@DisplayName("MyProfileController")
class MyProfileControllerTest {

    private static final String CALLER_ID = "11111111-1111-1111-1111-111111111111";
    private static final CallerContext PORTAL_CALLER =
            new CallerContext(CALLER_ID, UserType.PORTAL, false, false);

    /** Stands in for {@code DynamicCollectionRouter}'s two-segment mappings. */
    @RestController
    @RequestMapping("/api")
    static class RouterStub {
        @GetMapping("/{collectionName}/{id}")
        String read() {
            return "router";
        }

        @PatchMapping("/{collectionName}/{id}")
        String patch() {
            return "router";
        }
    }

    private final CollectionDefinition usersDef = SystemCollectionDefinitions.users();
    private QueryEngine queryEngine;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queryEngine = mock(QueryEngine.class);
        CollectionRegistry registry = mock(CollectionRegistry.class);
        when(registry.get("users")).thenReturn(usersDef);
        mvc = MockMvcBuilders
                .standaloneSetup(new MyProfileController(queryEngine, registry), new RouterStub())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Map<String, Object> userRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", CALLER_ID);
        row.put("tenantId", "tenant-1");
        row.put("email", "member@example.com");
        row.put("firstName", "Sam");
        row.put("lastName", "Member");
        row.put("locale", "en");
        row.put("timezone", "Europe/Lisbon");
        row.put("userType", "PORTAL");
        row.put("profileId", "portal-profile");
        row.put("settings", Map.of("secret", "x"));
        return row;
    }

    private ResultActions perform(CallerContext caller, RequestBuilder request) throws Exception {
        if (caller == null) {
            return mvc.perform(request);
        }
        return ScopedValue.where(CallerContext.CURRENT, caller).call(() -> mvc.perform(request));
    }

    private static RequestBuilder patchAttrs(String attributesJson) {
        return patch("/api/me/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\":{\"type\":\"users\",\"attributes\":" + attributesJson + "}}");
    }

    @Nested
    @DisplayName("GET")
    class Get {

        @Test
        @DisplayName("returns the caller's own record in the JSON:API shape, without other columns")
        void returnsOwnRecord() throws Exception {
            when(queryEngine.getById(usersDef, CALLER_ID)).thenReturn(Optional.of(userRow()));

            perform(PORTAL_CALLER, get("/api/me/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.type").value("users"))
                    .andExpect(jsonPath("$.data.id").value(CALLER_ID))
                    .andExpect(jsonPath("$.data.attributes.email").value("member@example.com"))
                    .andExpect(jsonPath("$.data.attributes.firstName").value("Sam"))
                    .andExpect(jsonPath("$.data.attributes.lastName").value("Member"))
                    .andExpect(jsonPath("$.data.attributes.locale").value("en"))
                    .andExpect(jsonPath("$.data.attributes.timezone").value("Europe/Lisbon"))
                    .andExpect(jsonPath("$.data.attributes.userType").value("PORTAL"))
                    .andExpect(jsonPath("$.data.attributes.profileId").doesNotExist())
                    .andExpect(jsonPath("$.data.attributes.settings").doesNotExist())
                    .andExpect(jsonPath("$.data.attributes.tenantId").doesNotExist());
        }

        @Test
        @DisplayName("404 when no CallerContext is bound")
        void noCallerIsNotFound() throws Exception {
            perform(null, get("/api/me/profile")).andExpect(status().isNotFound());
            verify(queryEngine, never()).getById(any(), anyString());
        }

        @Test
        @DisplayName("404 when the caller has no users row")
        void missingRowIsNotFound() throws Exception {
            when(queryEngine.getById(usersDef, CALLER_ID)).thenReturn(Optional.empty());

            perform(PORTAL_CALLER, get("/api/me/profile")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH")
    class Patch {

        @Test
        @DisplayName("updates the allow-listed fields on the caller's own id inside SelfProfileWriteContext")
        void updatesAllowedFields() throws Exception {
            AtomicReference<SelfProfileWriteContext.Grant> boundGrant = new AtomicReference<>();
            AtomicReference<Map<String, Object>> written = new AtomicReference<>();
            when(queryEngine.update(eq(usersDef), eq(CALLER_ID), anyMap())).thenAnswer(inv -> {
                boundGrant.set(SelfProfileWriteContext.current().orElse(null));
                written.set(inv.getArgument(2));
                Map<String, Object> row = userRow();
                row.putAll(inv.getArgument(2));
                return Optional.of(row);
            });

            perform(PORTAL_CALLER, patchAttrs("""
                    {"firstName":" Alex ","lastName":"Doe","locale":"pt_BR","timezone":"UTC"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(CALLER_ID))
                    .andExpect(jsonPath("$.data.attributes.firstName").value("Alex"))
                    .andExpect(jsonPath("$.data.attributes.locale").value("pt_BR"))
                    .andExpect(jsonPath("$.data.attributes.timezone").value("UTC"));

            assertThat(written.get()).containsOnly(
                    Map.entry("firstName", "Alex"), Map.entry("lastName", "Doe"),
                    Map.entry("locale", "pt_BR"), Map.entry("timezone", "UTC"));
            assertThat(boundGrant.get()).as("write ran inside SelfProfileWriteContext").isNotNull();
            assertThat(boundGrant.get().userId()).isEqualTo(CALLER_ID);
            assertThat(boundGrant.get().allowedFields())
                    .containsExactlyInAnyOrder("firstName", "lastName", "locale", "timezone");
            assertThat(SelfProfileWriteContext.current()).as("grant does not outlive the write").isEmpty();
        }

        @Test
        @DisplayName("an INTERNAL caller without MANAGE_USERS may update their own profile too")
        void internalCallerUpdates() throws Exception {
            when(queryEngine.update(eq(usersDef), eq(CALLER_ID), anyMap()))
                    .thenReturn(Optional.of(userRow()));

            perform(new CallerContext(CALLER_ID, UserType.INTERNAL, false, false),
                    patchAttrs("{\"timezone\":\"America/New_York\"}"))
                    .andExpect(status().isOk());

            verify(queryEngine).update(usersDef, CALLER_ID, Map.of("timezone", "America/New_York"));
        }

        @ParameterizedTest(name = "{0} → FIELD_NOT_EDITABLE")
        @ValueSource(strings = {"email", "status", "profileId", "userType", "managerId",
                "mfaEnabled", "settings", "username", "tenantId", "id"})
        @DisplayName("any non-allow-listed attribute → 400 FIELD_NOT_EDITABLE with its pointer")
        void rejectsOtherFields(String field) throws Exception {
            perform(PORTAL_CALLER, patchAttrs("{\"timezone\":\"UTC\",\"" + field + "\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].status").value("400"))
                    .andExpect(jsonPath("$.errors[0].code").value("FIELD_NOT_EDITABLE"))
                    .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/" + field));

            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }

        @ParameterizedTest(name = "locale {0} → VALIDATION_FAILED")
        @ValueSource(strings = {"\"klingon\"", "\"xx\"", "\"en_ZZ\"", "\"\"", "null", "42", "\"en_US_POSIX\""})
        @DisplayName("an invalid locale tag → 400 VALIDATION_FAILED")
        void rejectsInvalidLocale(String json) throws Exception {
            perform(PORTAL_CALLER, patchAttrs("{\"locale\":" + json + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/locale"));

            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }

        @ParameterizedTest(name = "timezone {0} → VALIDATION_FAILED")
        @ValueSource(strings = {"\"Mars/Olympus\"", "\"+02:00\"", "\"\"", "null"})
        @DisplayName("an invalid IANA zone → 400 VALIDATION_FAILED")
        void rejectsInvalidTimezone(String json) throws Exception {
            perform(PORTAL_CALLER, patchAttrs("{\"timezone\":" + json + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/timezone"));

            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }

        @Test
        @DisplayName("a name over 100 characters → 400 VALIDATION_FAILED")
        void rejectsLongName() throws Exception {
            String longName = "a".repeat(MyProfileController.MAX_NAME_LENGTH + 1);

            perform(PORTAL_CALLER, patchAttrs("{\"lastName\":\"" + longName + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/lastName"));

            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }

        @Test
        @DisplayName("reports every rejected attribute at once")
        void reportsAllErrors() throws Exception {
            perform(PORTAL_CALLER, patchAttrs("{\"email\":\"x@y.z\",\"timezone\":\"Nowhere\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].source.pointer").value(hasItem("/data/attributes/email")))
                    .andExpect(jsonPath("$.errors[*].source.pointer").value(hasItem("/data/attributes/timezone")));
        }

        @Test
        @DisplayName("404 when no CallerContext is bound")
        void noCallerIsNotFound() throws Exception {
            perform(null, patchAttrs("{\"timezone\":\"UTC\"}")).andExpect(status().isNotFound());
            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }

        @Test
        @DisplayName("an empty attribute set writes nothing and returns the current profile")
        void emptyPatchIsNoOp() throws Exception {
            when(queryEngine.getById(usersDef, CALLER_ID)).thenReturn(Optional.of(userRow()));

            perform(PORTAL_CALLER, patchAttrs("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.attributes.timezone").value("Europe/Lisbon"));

            verify(queryEngine, never()).update(any(), anyString(), anyMap());
        }
    }

    @Test
    @DisplayName("locale tags: language with an optional ISO region or UN M.49 area")
    void localeTags() {
        assertThat(MyProfileController.isSupportedLocale("en")).isTrue();
        assertThat(MyProfileController.isSupportedLocale("en_US")).isTrue();
        assertThat(MyProfileController.isSupportedLocale("pt-BR")).isTrue();
        assertThat(MyProfileController.isSupportedLocale("es-419")).isTrue();
        assertThat(MyProfileController.isSupportedLocale("AR")).isTrue();
        assertThat(MyProfileController.isSupportedLocale("zz")).isFalse();
        assertThat(MyProfileController.isSupportedLocale("en_GBR")).isFalse();
        assertThat(MyProfileController.isSupportedLocale(null)).isFalse();
    }
}
