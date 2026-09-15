package io.kelta.mcp.resource.docs;

import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the classpath copies under {@code src/main/resources/docs/authoring/}
 * to the repo-root source of truth at {@code docs/authoring/*.md} — the same
 * freshness-guard shape as the CLI's {@code docsgen.test.ts}. Fails loud (with
 * a diff-able message) if someone edits one copy and forgets the other.
 */
class DocResourceTest {

    private static final List<String> TOPICS = List.of(
            "jsonapi", "page-layouts", "list-views", "dashboards", "ui-pages", "ui-menus");

    @ParameterizedTest
    @ValueSource(strings = {"jsonapi", "page-layouts", "list-views", "dashboards", "ui-pages", "ui-menus"})
    void classpathCopyMatchesRepoRootSource(String topic) {
        String classpathBody = DocResource.readClasspathDoc(topic);
        String repoRootBody = readRepoRootDoc(topic);
        assertThat(classpathBody)
                .as("src/main/resources/docs/authoring/%s.md must match docs/authoring/%s.md — "
                        + "copy the repo-root file over the classpath one", topic, topic)
                .isEqualTo(repoRootBody);
    }

    @Test
    void everyTopicHasAClasspathCopy() {
        for (String topic : TOPICS) {
            assertThat(DocResource.readClasspathDoc(topic)).isNotBlank();
        }
    }

    @Test
    void exposesTheKeltaDocsUri() {
        DocResource resource = new DocResource("list-views", "test");
        assertThat(resource.uri()).isEqualTo("kelta://docs/list-views");
        assertThat(resource.toSpecification().resource().mimeType()).isEqualTo("text/markdown");
    }

    @Test
    void readHandlerReturnsTheDocBody() {
        DocResource resource = new DocResource("jsonapi", "test");
        ReadResourceResult result = resource.toSpecification().readHandler()
                .apply(null, new ReadResourceRequest("kelta://docs/jsonapi"));
        TextResourceContents contents = (TextResourceContents) result.contents().get(0);
        assertThat(contents.uri()).isEqualTo("kelta://docs/jsonapi");
        assertThat(contents.text()).contains("pageSizeClamped");
    }

    private static String readRepoRootDoc(String topic) {
        try {
            return Files.readString(Path.of("../docs/authoring/" + topic + ".md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
