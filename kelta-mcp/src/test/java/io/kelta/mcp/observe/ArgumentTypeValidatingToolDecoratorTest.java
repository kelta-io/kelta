package io.kelta.mcp.observe;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.kelta.mcp.auth.RequestPatHolder;
import io.kelta.mcp.client.GatewayHttpClient;
import io.kelta.mcp.config.McpProperties;
import io.kelta.mcp.tool.Schemas;
import io.kelta.mcp.tool.admin.ApplyListViewTool;
import io.kelta.mcp.tool.admin.UpdateCollectionTool;
import io.kelta.mcp.tool.user.ListApprovalsTool;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

class ArgumentTypeValidatingToolDecoratorTest {

    private final ArgumentTypeValidatingToolDecorator decorator = new ArgumentTypeValidatingToolDecorator();
    private final AtomicInteger calls = new AtomicInteger();

    private SyncToolSpecification stub() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Schemas.string("Name."));
        properties.put("count", Schemas.integer("Count.", 1, null));
        properties.put("ratio", Map.of("type", "number"));
        properties.put("active", Schemas.bool("Active.", false));
        properties.put("columns", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("filter", Schemas.freeObject("Filter."));
        properties.put("defaultValue", Schemas.anyScalar("Any scalar."));
        properties.put("untyped", Map.of("description", "No declared type."));
        Tool tool = Tool.builder()
                .name("stub_tool")
                .description("test")
                .inputSchema(Schemas.object(properties, List.of("name")))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((ctx, req) -> {
                    calls.incrementAndGet();
                    return CallToolResult.builder().content(List.of(new TextContent("ok"))).build();
                })
                .build();
    }

    private CallToolResult call(Map<String, Object> args) {
        return decorator.decorate(stub()).callHandler().apply(
                McpTransportContext.EMPTY, new CallToolRequest("stub_tool", args, null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstError(CallToolResult result) {
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        return ((List<Map<String, Object>>) structured.get("errors")).get(0);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private void assertRejected(CallToolResult result, String argument, String expectedType, String actualType) {
        assertThat(calls).hasValue(0);
        assertThat(result.isError()).isTrue();
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.structuredContent()).get("status")).isEqualTo(400);
        Map<String, Object> error = firstError(result);
        assertThat(error.get("status")).isEqualTo("400");
        assertThat(error.get("code")).isEqualTo("INVALID_ARGUMENT_TYPE");
        assertThat(error.get("source")).isEqualTo(Map.of("parameter", argument));
        assertThat(text(result))
                .contains("\"" + argument + "\"")
                .contains("must be " + expectedType)
                .contains("got " + actualType);
    }

    @Test
    void rejectsNumberWhereStringDeclared() {
        assertRejected(call(Map.of("name", 42)), "name", "string", "number");
    }

    @Test
    void rejectsStringWhereBooleanDeclared() {
        assertRejected(call(Map.of("name", "a", "active", "true")), "active", "boolean", "string");
    }

    @Test
    void rejectsObjectWhereArrayDeclared() {
        assertRejected(call(Map.of("name", "a", "columns", Map.of("0", "name"))), "columns", "array", "object");
    }

    @Test
    void rejectsArrayWhereObjectDeclared() {
        assertRejected(call(Map.of("name", "a", "filter", List.of("x"))), "filter", "object", "array");
    }

    @Test
    void rejectsStringWhereIntegerDeclaredAndFractionalNumber() {
        assertRejected(call(Map.of("name", "a", "count", "5")), "count", "integer", "string");
        assertRejected(call(Map.of("name", "a", "count", 5.5)), "count", "integer", "number");
    }

    @Test
    void reportsEveryWrongArgument() {
        CallToolResult result = call(Map.of("name", 1, "active", "yes"));

        assertThat(calls).hasValue(0);
        @SuppressWarnings("unchecked")
        List<Object> errors = (List<Object>) ((Map<String, Object>) result.structuredContent()).get("errors");
        assertThat(errors).hasSize(2);
        assertThat(text(result)).contains("\"name\"").contains("\"active\"");
    }

    @Test
    void correctlyTypedCallPassesThrough() {
        CallToolResult result = call(Map.of(
                "name", "a",
                "count", 5,
                "ratio", 0.5,
                "active", true,
                "columns", List.of("name"),
                "filter", Map.of("status", "OPEN"),
                "defaultValue", 3));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(calls).hasValue(1);
    }

    @Test
    void integralDoubleAndLongAreIntegers() {
        call(Map.of("name", "a", "count", 5.0));
        call(Map.of("name", "a", "count", 5L));

        assertThat(calls).hasValue(2);
    }

    @Test
    void omittedOrNullOptionalArgumentPassesThrough() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("name", "a");
        withNull.put("active", null);

        call(Map.of("name", "a"));
        call(withNull);

        assertThat(calls).hasValue(2);
    }

    @Test
    void undeclaredAndUntypedArgumentsAreLeftToTheTool() {
        call(Map.of("name", "a", "untyped", 7, "extra", List.of()));

        assertThat(calls).hasValue(1);
    }

    @Test
    void anyOfDeclaredTypesIsAccepted() {
        call(Map.of("name", "a", "defaultValue", true));
        call(Map.of("name", "a", "defaultValue", "x"));
        CallToolResult array = call(Map.of("name", "a", "defaultValue", List.of()));

        assertThat(calls).hasValue(2);
        assertThat(array.isError()).isTrue();
        assertThat(text(array)).contains("must be string or number or boolean or null");
    }

    /** Real tools behind the decorator: a wrong-typed argument never reaches the gateway. */
    @Nested
    class RealTools {

        private WireMockServer wm;
        private GatewayHttpClient client;

        @BeforeEach
        void setUp() {
            wm = new WireMockServer(0);
            wm.start();
            client = new GatewayHttpClient(
                    RestClient.builder(),
                    new McpProperties("http://localhost:" + wm.port(), 30, 60_000, null));
            RequestPatHolder.set("klt_arg_type_test");
        }

        @AfterEach
        void tearDown() {
            RequestPatHolder.clear();
            wm.stop();
        }

        private CallToolResult call(SyncToolSpecification spec, Map<String, Object> args) {
            return decorator.decorate(spec).callHandler().apply(
                    McpTransportContext.EMPTY, new CallToolRequest(spec.tool().name(), args, null));
        }

        private void assertRejectedBeforeGateway(CallToolResult result, String argument) {
            assertThat(result.isError()).isTrue();
            assertThat(((Map<?, ?>) result.structuredContent()).get("status")).isEqualTo(400);
            assertThat(firstError(result).get("source")).isEqualTo(Map.of("parameter", argument));
            wm.verify(0, anyRequestedFor(anyUrl()));
        }

        @Test
        void listApprovalsNumericStatusIsRejectedNotSent() {
            assertRejectedBeforeGateway(
                    call(new ListApprovalsTool(client).toSpecification(), Map.of("status", 5)),
                    "status");
        }

        @Test
        void listApprovalsStringPageSizeIsRejectedNotSent() {
            assertRejectedBeforeGateway(
                    call(new ListApprovalsTool(client).toSpecification(), Map.of("pageSize", "50")),
                    "pageSize");
        }

        @Test
        void updateCollectionNumericIdIsRejectedNotSent() {
            assertRejectedBeforeGateway(
                    call(new UpdateCollectionTool(client).toSpecification(),
                            Map.of("id", 123, "displayName", "Projects")),
                    "id");
        }

        @Test
        void applyListViewObjectNameIsRejectedNotSent() {
            assertRejectedBeforeGateway(
                    call(new ApplyListViewTool(client).toSpecification(), Map.of(
                            "collectionName", "projects",
                            "name", Map.of("en", "Main"),
                            "columns", List.of("name"))),
                    "name");
        }
    }
}
