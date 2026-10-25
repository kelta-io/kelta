package io.kelta.mcp.observe;

import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.config.McpProperties;
import io.kelta.mcp.tool.Schemas;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitedToolDecoratorTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private RateLimiter limiter;
    private RateLimitedToolDecorator decorator;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter(new McpProperties(
                "http://gw", 30, 60_000,
                new McpProperties.RateLimit(2, 0.0)));
        decorator = new RateLimitedToolDecorator(limiter, registry);
        RequestPatHolder.set("klt_decorator_test");
    }

    @AfterEach
    void tearDown() {
        RequestPatHolder.clear();
    }

    private SyncToolSpecification stub(AtomicInteger calls) {
        Tool tool = Tool.builder()
                .name("stub")
                .description("test")
                .inputSchema(Schemas.empty())
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((ctx, req) -> {
                    calls.incrementAndGet();
                    return CallToolResult.builder().content(List.of(new TextContent("ok"))).build();
                })
                .build();
    }

    @Test
    void allowsUpToCapacityThenReturnsRateLimitedResult() {
        AtomicInteger calls = new AtomicInteger();
        SyncToolSpecification decorated = decorator.decorate(stub(calls), "user");

        for (int i = 0; i < 2; i++) {
            CallToolResult r = decorated.callHandler().apply(null,
                    new CallToolRequest("stub", Map.of(), null));
            assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        }

        CallToolResult limited = decorated.callHandler().apply(null,
                new CallToolRequest("stub", Map.of(), null));
        assertThat(limited.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((TextContent) limited.content().get(0)).text())
                .contains("Rate limit exceeded");
        // Inner handler should NOT have been invoked for the limited call.
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void rateLimitedCounterIncrements() {
        AtomicInteger calls = new AtomicInteger();
        SyncToolSpecification decorated = decorator.decorate(stub(calls), "user");

        // Exhaust the bucket.
        decorated.callHandler().apply(null, new CallToolRequest("stub", Map.of(), null));
        decorated.callHandler().apply(null, new CallToolRequest("stub", Map.of(), null));
        decorated.callHandler().apply(null, new CallToolRequest("stub", Map.of(), null));
        decorated.callHandler().apply(null, new CallToolRequest("stub", Map.of(), null));

        assertThat(registry.counter("mcp.tool.rate_limited",
                "tool", "stub", "profile", "user").count()).isEqualTo(2.0);
    }
}
