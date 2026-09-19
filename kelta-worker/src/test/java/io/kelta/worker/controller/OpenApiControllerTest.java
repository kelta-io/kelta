package io.kelta.worker.controller;

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
import static org.mockito.Mockito.mock;
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
            when(openApiGenerator.generate(any(), eq("https://api.kelta.io")))
                    .thenReturn(Map.of("openapi", "3.0.3"));

            var response = controller.getOpenApiSpec("tenant-1");
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }
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
