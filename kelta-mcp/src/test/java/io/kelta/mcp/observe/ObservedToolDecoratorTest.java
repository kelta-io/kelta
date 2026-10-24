package io.kelta.mcp.observe;

import io.kelta.mcp.tool.Schemas;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservedToolDecoratorTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final ObservedToolDecorator decorator = new ObservedToolDecorator(registry);

    private SyncToolSpecification stub(BiFunction<McpTransportContext, CallToolRequest, CallToolResult> handler) {
        Tool tool = Tool.builder()
                .name("stub_tool")
                .description("test")
                .inputSchema(Schemas.empty())
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    @Test
    void recordsSuccessTimerAndCounter() {
        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) ->
                CallToolResult.builder().content(List.of(new TextContent("ok"))).build()), "user");

        decorated.callHandler().apply(McpTransportContext.EMPTY,
                new CallToolRequest("stub_tool", Map.of(), null));

        assertThat(registry.counter("mcp.tool.calls",
                "tool", "stub_tool", "profile", "user", "status", "success")
                .count()).isEqualTo(1.0);
        assertThat(registry.timer("mcp.tool.call.duration",
                "tool", "stub_tool", "profile", "user", "status", "success")
                .count()).isEqualTo(1L);
    }

    @Test
    void taggedAsErrorWhenIsErrorTrue() {
        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) ->
                CallToolResult.builder().isError(true)
                        .content(List.of(new TextContent("nope"))).build()), "user");

        decorated.callHandler().apply(McpTransportContext.EMPTY,
                new CallToolRequest("stub_tool", Map.of(), null));

        assertThat(registry.counter("mcp.tool.calls",
                "tool", "stub_tool", "profile", "user", "status", "error")
                .count()).isEqualTo(1.0);
    }

    @Test
    void taggedAsExceptionWhenHandlerThrows() {
        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) -> {
            throw new RuntimeException("boom");
        }), "admin");

        assertThatThrownBy(() ->
                decorated.callHandler().apply(McpTransportContext.EMPTY,
                        new CallToolRequest("stub_tool", Map.of(), null)))
                .isInstanceOf(RuntimeException.class);

        assertThat(registry.counter("mcp.tool.calls",
                "tool", "stub_tool", "profile", "admin", "status", "exception")
                .count()).isEqualTo(1.0);
    }

    @Test
    void mdcIsPopulatedDuringCallAndClearedAfter() {
        AtomicReference<String> seenTool = new AtomicReference<>();
        AtomicReference<String> seenProfile = new AtomicReference<>();

        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) -> {
            seenTool.set(MDC.get("mcpTool"));
            seenProfile.set(MDC.get("mcpProfile"));
            return CallToolResult.builder().content(List.of(new TextContent("ok"))).build();
        }), "user");

        decorated.callHandler().apply(McpTransportContext.EMPTY,
                new CallToolRequest("stub_tool", Map.of(), null));

        assertThat(seenTool.get()).isEqualTo("stub_tool");
        assertThat(seenProfile.get()).isEqualTo("user");
        assertThat(MDC.get("mcpTool")).isNull();
        assertThat(MDC.get("mcpProfile")).isNull();
    }

    @Test
    void mdcIsClearedEvenWhenHandlerThrows() {
        SyncToolSpecification decorated = decorator.decorate(stub((ctx, req) -> {
            throw new RuntimeException("boom");
        }), "user");

        try {
            decorated.callHandler().apply(McpTransportContext.EMPTY,
                    new CallToolRequest("stub_tool", Map.of(), null));
        } catch (RuntimeException ignored) {
        }

        assertThat(MDC.get("mcpTool")).isNull();
        assertThat(MDC.get("mcpProfile")).isNull();
    }
}
