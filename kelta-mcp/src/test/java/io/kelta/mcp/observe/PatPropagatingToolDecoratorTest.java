package io.kelta.mcp.observe;

import io.kelta.mcp.auth.KeltaTransportContextExtractor;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.auth.RequestSlugHolder;
import io.kelta.mcp.tool.Schemas;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class PatPropagatingToolDecoratorTest {

    private final PatPropagatingToolDecorator decorator = new PatPropagatingToolDecorator();

    private SyncToolSpecification stub(BiFunction<McpTransportContext, CallToolRequest, CallToolResult> handler) {
        Tool tool = Tool.builder()
                .name("stub")
                .description("test")
                .inputSchema(Schemas.empty())
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    @Test
    void copiesPatFromTransportContextIntoHolderForDurationOfCall() {
        AtomicReference<String> seen = new AtomicReference<>();
        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) -> {
            seen.set(RequestPatHolder.get());
            return CallToolResult.builder().content(List.of(new TextContent("ok"))).build();
        }));

        McpTransportContext context = McpTransportContext.create(
                Map.of(KeltaTransportContextExtractor.PAT_KEY, "klt_propagated"));

        decorated.callHandler().apply(context, new CallToolRequest("stub", Map.of(), null));

        assertThat(seen.get()).isEqualTo("klt_propagated");
        assertThat(RequestPatHolder.get()).isNull(); // cleared after
    }

    @Test
    void clearsHolderEvenWhenInnerHandlerThrows() {
        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) -> {
            throw new RuntimeException("boom");
        }));

        McpTransportContext context = McpTransportContext.create(
                Map.of(KeltaTransportContextExtractor.PAT_KEY, "klt_x"));

        try {
            decorated.callHandler().apply(context, new CallToolRequest("stub", Map.of(), null));
        } catch (RuntimeException ignored) {
        }

        assertThat(RequestPatHolder.get()).isNull();
    }

    @Test
    void doesNothingWhenContextHasNoPat() {
        AtomicReference<String> seen = new AtomicReference<>();
        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) -> {
            seen.set(RequestPatHolder.get());
            return CallToolResult.builder().content(List.of(new TextContent("ok"))).build();
        }));

        decorated.callHandler().apply(McpTransportContext.EMPTY,
                new CallToolRequest("stub", Map.of(), null));

        assertThat(seen.get()).isNull();
    }

    @Test
    void copiesTenantSlugFromTransportContextIntoHolder() {
        AtomicReference<String> seenPat = new AtomicReference<>();
        AtomicReference<String> seenSlug = new AtomicReference<>();
        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) -> {
            seenPat.set(RequestPatHolder.get());
            seenSlug.set(RequestSlugHolder.get());
            return CallToolResult.builder().content(List.of(new TextContent("ok"))).build();
        }));

        McpTransportContext context = McpTransportContext.create(Map.of(
                KeltaTransportContextExtractor.PAT_KEY, "klt_session",
                KeltaTransportContextExtractor.TENANT_SLUG_KEY, "threadline-clothing"));

        decorated.callHandler().apply(context, new CallToolRequest("stub", Map.of(), null));

        assertThat(seenPat.get()).isEqualTo("klt_session");
        assertThat(seenSlug.get()).isEqualTo("threadline-clothing");
        assertThat(RequestPatHolder.get()).isNull();
        assertThat(RequestSlugHolder.get()).isNull();
    }

    @Test
    void tolerantOfNullContext() {
        // Defensive contract: if a caller bypasses the SDK and supplies null,
        // we don't NPE — we just don't populate the holders.
        AtomicReference<String> seen = new AtomicReference<>();
        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) -> {
            seen.set(RequestPatHolder.get());
            return CallToolResult.builder().content(List.of(new TextContent("ok"))).build();
        }));

        decorated.callHandler().apply(null, new CallToolRequest("stub", Map.of(), null));

        assertThat(seen.get()).isNull();
        assertThat(RequestPatHolder.get()).isNull();
    }
}
