package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.OpenApiGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OpenApiController Tests")
class OpenApiControllerTest {

    private CollectionRegistry collectionRegistry;
    private OpenApiGenerator openApiGenerator;
    private OpenApiController controller;

    @BeforeEach
    void setUp() {
        collectionRegistry = mock(CollectionRegistry.class);
        openApiGenerator = mock(OpenApiGenerator.class);
        controller = new OpenApiController(collectionRegistry, openApiGenerator, "https://api.kelta.io");
    }

    @Nested
    @DisplayName("getOpenApiSpec")
    class GetOpenApiSpec {

        @Test
        void shouldReturn401ForNullTenantId() {
            var response = controller.getOpenApiSpec(null);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        }

        @Test
        void shouldReturn401ForBlankTenantId() {
            var response = controller.getOpenApiSpec("  ");
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        }

        @Test
        void shouldReturnSpecForValidTenant() {
            when(collectionRegistry.getAllForCurrentTenant()).thenReturn(List.of());
            when(openApiGenerator.generate(any(), eq("https://api.kelta.io"), eq("acme")))
                    .thenReturn(Map.of("openapi", "3.0.3"));

            var response = TenantContext.callWithTenant("tenant-1", "acme",
                    () -> controller.getOpenApiSpec("tenant-1"));
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(Map.of("openapi", "3.0.3"), response.getBody());
        }

        @Test
        void shouldPassTenantSlugFromContextToGenerator() {
            when(collectionRegistry.getAllForCurrentTenant()).thenReturn(List.of());

            TenantContext.callWithTenant("tenant-1", "acme", () -> controller.getOpenApiSpec("tenant-1"));

            verify(openApiGenerator).generate(any(), eq("https://api.kelta.io"), eq("acme"));
        }

        @Test
        void shouldGenerateWithoutPrefixWhenNoSlugIsBound() {
            when(collectionRegistry.getAllForCurrentTenant()).thenReturn(List.of());

            var response = TenantContext.callWithTenant("tenant-1", () -> controller.getOpenApiSpec("tenant-1"));

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(openApiGenerator).generate(any(), eq("https://api.kelta.io"), isNull());
        }

        @Test
        void missingSlugProducesTheUnprefixedDocument() {
            var realController = new OpenApiController(collectionRegistry, new OpenApiGenerator(),
                    "https://api.kelta.io");
            when(collectionRegistry.getAllForCurrentTenant()).thenReturn(List.of());

            var withSlug = TenantContext.callWithTenant("tenant-1", "acme",
                    () -> realController.getOpenApiSpec("tenant-1"));
            var withoutSlug = TenantContext.callWithTenant("tenant-1",
                    () -> realController.getOpenApiSpec("tenant-1"));

            assertEquals(List.of(Map.of("url", "https://api.kelta.io/acme")), serversOf(withSlug.getBody()));
            assertEquals(List.of(Map.of("url", "https://api.kelta.io")), serversOf(withoutSlug.getBody()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object serversOf(Object body) {
        return ((Map<String, Object>) body).get("servers");
    }

    @Nested
    @DisplayName("getSwaggerUi")
    class GetSwaggerUi {

        @Test
        void shouldReturn401ForNullTenantId() {
            var request = mock(HttpServletRequest.class);
            var response = controller.getSwaggerUi(null, request);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertTrue(response.getBody().contains("401"));
        }

        @Test
        void shouldReturnHtmlForValidTenant() {
            var request = mock(HttpServletRequest.class);
            when(request.getRequestURL()).thenReturn(new StringBuffer("https://api.kelta.io/api/docs"));

            var response = controller.getSwaggerUi("tenant-1", request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().contains("swagger-ui"));
            assertTrue(response.getBody().contains("openapi.json"));
        }
    }
}
