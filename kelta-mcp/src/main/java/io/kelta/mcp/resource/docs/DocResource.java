package io.kelta.mcp.resource.docs;

import io.kelta.mcp.resource.AdminResource;
import io.kelta.mcp.resource.UserResource;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A single {@code docs/authoring/<topic>.md} reference doc, served verbatim
 * as {@code kelta://docs/<topic>} on both {@code /mcp/user} and {@code
 * /mcp/admin} — metadata authoring is relevant to both data-plane and
 * control-plane clients. Body is read from a classpath copy under {@code
 * docs/authoring/} (pinned against the repo-root source by {@code
 * DocResourceTest}), not the repo file directly — the jar has no access to
 * the repo tree at runtime.
 */
public final class DocResource implements UserResource, AdminResource {

    private final String topic;
    private final String description;
    private final String body;

    public DocResource(String topic, String description) {
        this.topic = topic;
        this.description = description;
        this.body = readClasspathDoc(topic);
    }

    static String readClasspathDoc(String topic) {
        try {
            return new ClassPathResource("docs/authoring/" + topic + ".md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing classpath doc for topic " + topic, e);
        }
    }

    public String uri() {
        return "kelta://docs/" + topic;
    }

    @Override
    public SyncResourceSpecification toSpecification() {
        Resource resource = Resource.builder()
                .uri(uri())
                .name("docs-" + topic)
                .description(description)
                .mimeType("text/markdown")
                .build();

        return new SyncResourceSpecification(resource, (context, request) ->
                new ReadResourceResult(List.of(
                        new TextResourceContents(uri(), "text/markdown", body))));
    }
}
