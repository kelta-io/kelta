package io.kelta.worker.interceptor;

import io.kelta.worker.service.CerbosAuthorizationService;
import io.kelta.worker.service.CerbosPermissionResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CerbosFieldWriteSecurityAdvice Tests")
class CerbosFieldWriteSecurityAdviceTest {

    @Mock private CerbosAuthorizationService authzService;
    @Mock private CerbosPermissionResolver permissionResolver;

    private CerbosFieldWriteSecurityAdvice advice;

    @BeforeEach
    void setUp() {
        advice = new CerbosFieldWriteSecurityAdvice(new FieldWriteGuard(authzService), permissionResolver, true);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void setUpRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-User-Email", "user@example.com");
        request.addHeader("X-User-Profile-Id", "profile-1");
        request.addHeader("X-Cerbos-Scope", "tenant-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        lenient().when(permissionResolver.hasIdentity(any())).thenReturn(true);
        lenient().when(permissionResolver.getEmail(any())).thenReturn("user@example.com");
        lenient().when(permissionResolver.getProfileId(any())).thenReturn("profile-1");
        lenient().when(permissionResolver.getTenantId(any())).thenReturn("tenant-1");
    }

    @Nested
    @DisplayName("supports()")
    class Supports {
        @Test
        void shouldSupportPutRequests() {
            setUpRequest("PUT", "/api/contacts/123");
            assertThat(advice.supports(null, null, null)).isTrue();
        }

        @Test
        void shouldSupportPatchRequests() {
            setUpRequest("PATCH", "/api/contacts/123");
            assertThat(advice.supports(null, null, null)).isTrue();
        }

        @Test
        void shouldNotSupportGetRequests() {
            setUpRequest("GET", "/api/contacts/123");
            assertThat(advice.supports(null, null, null)).isFalse();
        }

        @Test
        void shouldNotSupportPostRequestsOutsideTheRouterCreateHandlers() throws Exception {
            setUpRequest("POST", "/api/contacts");
            assertThat(advice.supports(null, null, null)).isFalse();
            assertThat(advice.supports(bodyOf(OtherController.class.getDeclaredMethod("submit", Map.class)),
                    null, null)).isFalse();
        }

        @Test
        void shouldSupportPostToTheRouterCreate() throws Exception {
            setUpRequest("POST", "/api/contacts");
            assertThat(advice.supports(routerCreateBody(), null, null)).isTrue();
        }

        @Test
        void shouldSupportPostToTheRouterChildCreate() throws Exception {
            setUpRequest("POST", "/api/accounts/a1/contacts");
            assertThat(advice.supports(routerCreateChildBody(), null, null)).isTrue();
        }

        @Test
        void shouldNotSupportPostToTheRouterCreateOnMetadataPaths() throws Exception {
            setUpRequest("POST", "/api/collections");
            assertThat(advice.supports(routerCreateBody(), null, null)).isFalse();
        }

        @Test
        void shouldMatchMetadataPathsBySegment() {
            setUpRequest("PUT", "/api/flowsheet/123");
            assertThat(advice.supports(null, null, null)).isTrue();
        }

        @Test
        void shouldNotSupportAdminPaths() {
            setUpRequest("PUT", "/api/admin/settings");
            assertThat(advice.supports(null, null, null)).isFalse();
        }

        @Test
        void shouldNotSupportMetadataPaths() {
            setUpRequest("PUT", "/api/collections/contacts");
            assertThat(advice.supports(null, null, null)).isFalse();
        }

        @Test
        void shouldNotSupportWhenPermissionsDisabled() {
            var disabledAdvice = new CerbosFieldWriteSecurityAdvice(
                    new FieldWriteGuard(authzService), permissionResolver, false);
            setUpRequest("PUT", "/api/contacts/123");
            assertThat(disabledAdvice.supports(null, null, null)).isFalse();
        }
    }

    /** A controller whose POST body is not a record write. */
    static class OtherController {
        @SuppressWarnings("unused")
        public Object submit(Map<String, Object> body) {
            return body;
        }
    }

    private static org.springframework.core.MethodParameter bodyOf(java.lang.reflect.Method method) {
        return new org.springframework.core.MethodParameter(method, 0);
    }

    private static org.springframework.core.MethodParameter routerCreateBody() throws Exception {
        return new org.springframework.core.MethodParameter(
                io.kelta.runtime.router.DynamicCollectionRouter.class.getMethod("create",
                        String.class, Map.class, jakarta.servlet.http.HttpServletRequest.class), 1);
    }

    private static org.springframework.core.MethodParameter routerCreateChildBody() throws Exception {
        return new org.springframework.core.MethodParameter(
                io.kelta.runtime.router.DynamicCollectionRouter.class.getMethod("createChild",
                        String.class, String.class, String.class, Map.class,
                        jakarta.servlet.http.HttpServletRequest.class), 3);
    }

    @Nested
    @DisplayName("creates — the same rule as updates")
    class Creates {
        @Test
        @DisplayName("A create body loses the fields the caller may not write")
        void createStripsUnwritableFields() {
            setUpRequest("POST", "/api/contacts");
            when(authzService.batchCheckFieldAccess(
                    eq("user@example.com"), eq("profile-1"), eq("tenant-1"),
                    eq("contacts"), anyList(), eq("write")))
                    .thenReturn(List.of("name"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("internalScore", 95);
            Map<String, Object> body = Map.of("data", Map.of("type", "contacts", "attributes", attributes));

            advice.afterBodyRead(body, null, null, null, null);

            // Absent from the body, so the stored record gets the field's default (or null)
            assertThat(attributes).containsOnlyKeys("name");
        }

        @Test
        @DisplayName("A child create is stripped by the child collection's rules")
        void childCreateUsesChildRules() {
            setUpRequest("POST", "/api/accounts/a1/contacts");
            ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest()
                    .setAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                            Map.of("parentName", "accounts", "parentId", "a1", "childName", "contacts"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("write")))
                    .thenReturn(List.of("name"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", "123-45-6789");
            advice.afterBodyRead(Map.of("data", Map.of("attributes", attributes)), null, null, null, null);

            assertThat(attributes).containsOnlyKeys("name");
        }

        @Test
        @DisplayName("A flat (non-JSON:API) create body is stripped too")
        void flatCreateBody() {
            setUpRequest("POST", "/api/contacts");
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("write")))
                    .thenReturn(List.of("name"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "John");
            body.put("ssn", "123-45-6789");
            advice.afterBodyRead(body, null, null, null, null);

            assertThat(body).containsOnlyKeys("name");
        }
    }

    @Nested
    @DisplayName("relationships")
    class Relationships {
        @Test
        @DisplayName("A to-one relationship the caller may not write is removed, like its attribute")
        void stripsUnwritableRelationship() {
            setUpRequest("PATCH", "/api/contacts/c1");
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("write")))
                    .thenReturn(List.of("name", "account"));

            Map<String, Object> attributes = new LinkedHashMap<>(Map.of("name", "John"));
            Map<String, Object> relationships = new LinkedHashMap<>();
            relationships.put("account", Map.of("data", Map.of("type", "accounts", "id", "a1")));
            relationships.put("owner", Map.of("data", Map.of("type", "users", "id", "u1")));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("attributes", attributes);
            data.put("relationships", relationships);

            advice.afterBodyRead(Map.of("data", data), null, null, null, null);

            assertThat(attributes).containsOnlyKeys("name");
            assertThat(relationships).containsOnlyKeys("account");
        }

        @Test
        @DisplayName("A body with only relationships is checked")
        void relationshipsOnly() {
            setUpRequest("POST", "/api/contacts");
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("write")))
                    .thenReturn(List.of());

            Map<String, Object> relationships = new LinkedHashMap<>();
            relationships.put("owner", Map.of("data", Map.of("type", "users", "id", "u1")));

            advice.afterBodyRead(Map.of("data", Map.of("relationships", relationships)), null, null, null, null);

            assertThat(relationships).isEmpty();
        }
    }

    @Nested
    @DisplayName("afterBodyRead() — field stripping")
    class FieldStripping {
        @Test
        void shouldStripHiddenFields() {
            setUpRequest("PUT", "/api/contacts/123");
            // Only "name" is allowed for write; "ssn" is denied
            when(authzService.batchCheckFieldAccess(
                    eq("user@example.com"), eq("profile-1"), eq("tenant-1"),
                    eq("contacts"), anyList(), eq("write")))
                    .thenReturn(List.of("name"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", "123-45-6789");
            Map<String, Object> body = Map.of("data", Map.of("type", "contacts", "attributes", attributes));

            advice.afterBodyRead(body, null, null, null, null);

            assertThat(attributes).containsKey("name");
            assertThat(attributes).doesNotContainKey("ssn");
        }

        @Test
        void shouldStripReadOnlyFields() {
            setUpRequest("PUT", "/api/contacts/123");
            when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("write")))
                    .thenReturn(List.of("name"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("internalScore", 95);
            Map<String, Object> body = Map.of("data", Map.of("type", "contacts", "attributes", attributes));

            advice.afterBodyRead(body, null, null, null, null);

            assertThat(attributes).containsKey("name");
            assertThat(attributes).doesNotContainKey("internalScore");
        }

        @Test
        void shouldPassVisibleFieldsThrough() {
            setUpRequest("PUT", "/api/contacts/123");
            when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("write")))
                    .thenReturn(List.of("name", "email", "phone"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("email", "john@example.com");
            attributes.put("phone", "555-1234");
            Map<String, Object> body = Map.of("data", Map.of("type", "contacts", "attributes", attributes));

            advice.afterBodyRead(body, null, null, null, null);

            assertThat(attributes).containsKeys("name", "email", "phone");
        }

        @Test
        void shouldNotStripSystemFields() {
            setUpRequest("PUT", "/api/contacts/123");
            when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("write")))
                    .thenReturn(List.of()); // Deny all non-system fields

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("createdAt", "2026-01-01T00:00:00Z");
            attributes.put("updatedAt", "2026-01-01T00:00:00Z");
            attributes.put("createdBy", "admin");
            attributes.put("updatedBy", "admin");
            attributes.put("name", "John"); // This should be stripped
            Map<String, Object> body = Map.of("data", Map.of("type", "contacts", "attributes", attributes));

            advice.afterBodyRead(body, null, null, null, null);

            assertThat(attributes).containsKeys("createdAt", "updatedAt", "createdBy", "updatedBy");
            assertThat(attributes).doesNotContainKey("name");
        }

        @Test
        void shouldHandleEmptyAttributes() {
            setUpRequest("PUT", "/api/contacts/123");

            Map<String, Object> attributes = new LinkedHashMap<>();
            Map<String, Object> body = Map.of("data", Map.of("type", "contacts", "attributes", attributes));

            Object result = advice.afterBodyRead(body, null, null, null, null);
            assertThat(result).isNotNull();
        }

        @Test
        void shouldNavigateJsonApiStructure() {
            setUpRequest("PUT", "/api/contacts/123");
            when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("write")))
                    .thenReturn(List.of("name"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("hidden", "secret");

            // JSON:API structure with data.attributes
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "contacts");
            data.put("attributes", attributes);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", data);

            advice.afterBodyRead(body, null, null, null, null);

            assertThat(attributes).containsKey("name");
            assertThat(attributes).doesNotContainKey("hidden");
        }

        @Test
        void shouldFailClosedWhenCerbosUnreachable() {
            setUpRequest("PUT", "/api/contacts/123");
            when(authzService.batchCheckFieldAccess(any(), any(), any(), any(), anyList(), eq("write")))
                    .thenThrow(new RuntimeException("Cerbos connection refused"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("email", "john@example.com");
            attributes.put("createdAt", "2026-01-01"); // System field — should survive
            Map<String, Object> body = Map.of("data", Map.of("type", "contacts", "attributes", attributes));

            advice.afterBodyRead(body, null, null, null, null);

            // Fail-closed: all non-system fields stripped
            assertThat(attributes).doesNotContainKey("name");
            assertThat(attributes).doesNotContainKey("email");
            assertThat(attributes).containsKey("createdAt");
        }

        @Test
        void shouldHandleNonMapBody() {
            setUpRequest("PUT", "/api/contacts/123");
            String body = "not a map";
            Object result = advice.afterBodyRead(body, null, null, null, null);
            assertThat(result).isEqualTo("not a map");
        }
    }

    @Nested
    @DisplayName("child-resource routes")
    class ChildResourceRoutes {

        private void matchedChildRoute(String method, String path, Map<String, String> uriVariables) {
            setUpRequest(method, path);
            ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest()
                    .setAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                            uriVariables);
        }

        @Test
        @DisplayName("A child update is stripped by the child collection's field rules")
        void childUpdateUsesChildFieldRules() {
            matchedChildRoute("PATCH", "/api/accounts/a1/contacts/c1",
                    Map.of("parentName", "accounts", "parentId", "a1", "childName", "contacts", "childId", "c1"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("write")))
                    .thenReturn(List.of("name"));

            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "John");
            attributes.put("ssn", "123-45-6789");
            Map<String, Object> body = Map.of("data", Map.of("type", "contacts", "attributes", attributes));

            advice.afterBodyRead(body, null, null, null, null);

            assertThat(attributes).containsOnlyKeys("name");
            org.mockito.Mockito.verify(authzService, org.mockito.Mockito.never())
                    .batchCheckFieldAccess(any(), any(), any(), eq("accounts"), anyList(), any());
        }

        @Test
        @DisplayName("Without a matched child handler the first segment is the collection")
        void topLevelUpdateUsesFirstSegment() {
            matchedChildRoute("PUT", "/api/contacts/c1", Map.of("collectionName", "contacts", "id", "c1"));
            when(authzService.batchCheckFieldAccess(any(), any(), any(), eq("contacts"), anyList(), eq("write")))
                    .thenReturn(List.of("name"));

            Map<String, Object> attributes = new LinkedHashMap<>(Map.of("name", "John"));
            advice.afterBodyRead(Map.of("data", Map.of("attributes", attributes)), null, null, null, null);

            assertThat(attributes).containsOnlyKeys("name");
        }
    }
}
