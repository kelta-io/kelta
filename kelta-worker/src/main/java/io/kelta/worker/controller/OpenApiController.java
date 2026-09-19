package io.kelta.worker.controller;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.worker.service.OpenApiGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Serves auto-generated OpenAPI 3.0 specification and Swagger UI.
 *
 * <p>The spec is dynamically generated from the tenant's collection definitions.
 * Both endpoints require authentication to prevent schema information leakage.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/docs")
public class OpenApiController {

    private final CollectionRegistry collectionRegistry;
    private final OpenApiGenerator openApiGenerator;
    private final String serverUrl;

    public OpenApiController(
            CollectionRegistry collectionRegistry,
            OpenApiGenerator openApiGenerator,
            @Value("${kelta.api.docs.server-url:}") String serverUrl) {
        this.collectionRegistry = collectionRegistry;
        this.openApiGenerator = openApiGenerator;
        this.serverUrl = serverUrl;
    }

    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getOpenApiSpec(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {

        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        List<CollectionDefinition> collections = collectionRegistry.getAllForCurrentTenant();

        Map<String, Object> spec = openApiGenerator.generate(collections, serverUrl);
        return ResponseEntity.ok(spec);
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getSwaggerUi(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            HttpServletRequest request) {

        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("<html><body><h1>401 Unauthorized</h1><p>Authentication required to view API docs.</p></body></html>");
        }

        String specUrl = request.getRequestURL().toString().replace("/api/docs", "/api/docs/openapi.json");

        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Kelta Platform — API Documentation</title>
                    <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
                    <style>
                        body { margin: 0; background: #1a1b23; }
                        .swagger-ui .topbar { display: none; }
                        .swagger-ui { max-width: 1200px; margin: 0 auto; }
                    </style>
                </head>
                <body>
                    <div id="swagger-ui"></div>
                    <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                    <script>
                        SwaggerUIBundle({
                            url: '%s',
                            dom_id: '#swagger-ui',
                            deepLinking: true,
                            presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
                            layout: 'BaseLayout'
                        });
                    </script>
                </body>
                </html>
                """.formatted(specUrl);

        return ResponseEntity.ok(html);
    }
}
