package io.kelta.mcp.resource.user;

import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.resource.UserResourceTemplate;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Templated resource: the full schema (collection metadata + fields) for a
 * given collection. URI template: {@code kelta://collections/{name}}.
 */
@Component
public class CollectionSchemaResource implements UserResourceTemplate {

    static final String URI_TEMPLATE = "kelta://collections/{name}";
    private static final Pattern URI_PATTERN = Pattern.compile("^kelta://collections/([^/]+)$");

    private final GatewayHttpClient gateway;

    public CollectionSchemaResource(GatewayHttpClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public SyncResourceTemplateSpecification toSpecification() {
        ResourceTemplate template = ResourceTemplate.builder()
                .uriTemplate(URI_TEMPLATE)
                .name("collection-schema")
                .description("Full schema for a single collection: metadata plus all field definitions.")
                .mimeType("application/json")
                .build();

        return new SyncResourceTemplateSpecification(template, (context, request) -> {
            String requestUri = request.uri();
            Matcher m = URI_PATTERN.matcher(requestUri);
            String body;
            if (!m.matches()) {
                body = "{\"error\":\"unrecognized URI for collection-schema template: "
                        + requestUri + "\"}";
            } else {
                String name = URLDecode(m.group(1));
                String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
                GatewayHttpClient.Response schemaRes = gateway.get("/api/collections/" + encoded + "/schema");
                if (!schemaRes.isSuccess()) {
                    body = "{\"error\":\"gateway returned " + schemaRes.status()
                            + " for collection " + name + "\"}";
                } else {
                    body = schemaRes.body();
                }
            }
            return new ReadResourceResult(List.of(
                    new TextResourceContents(requestUri, "application/json", body)));
        });
    }

    private static String URLDecode(String s) {
        try {
            return URI.create("dummy:" + s).getSchemeSpecificPart();
        } catch (RuntimeException e) {
            return s;
        }
    }
}
