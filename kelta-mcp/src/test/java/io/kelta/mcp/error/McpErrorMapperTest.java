package io.kelta.mcp.error;

import io.kelta.mcp.client.GatewayHttpClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpErrorMapperTest {

    @Test
    void success200BecomesResultWithBodyAsText() {
        GatewayHttpClient.Response response = new GatewayHttpClient.Response(
                HttpStatus.OK, "{\"data\":[1,2,3]}");

        CallToolResult result = McpErrorMapper.toResult(response);

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.content()).hasSize(1);
        assertThat(((TextContent) result.content().get(0)).text())
                .isEqualTo("{\"data\":[1,2,3]}");
    }

    @Test
    void notFoundBecomesErrorResultWithStatusInMessage() {
        GatewayHttpClient.Response response = new GatewayHttpClient.Response(
                HttpStatus.NOT_FOUND, "{\"errors\":[{\"detail\":\"unknown collection\"}]}");

        CallToolResult result = McpErrorMapper.toResult(response);

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text).contains("404", "unknown collection");
    }

    @Test
    void serverError500BecomesErrorResult() {
        GatewayHttpClient.Response response = new GatewayHttpClient.Response(
                HttpStatus.INTERNAL_SERVER_ERROR, "boom");

        CallToolResult result = McpErrorMapper.toResult(response);

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((TextContent) result.content().get(0)).text())
                .contains("500", "boom");
    }

    @Test
    void exceptionBecomesErrorResult() {
        CallToolResult result = McpErrorMapper.fromException(
                new RuntimeException("connect timeout"));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((TextContent) result.content().get(0)).text())
                .contains("RuntimeException", "connect timeout");
    }

    @Test
    void redactionScrubsPatTokensFromSuccessBody() {
        GatewayHttpClient.Response response = new GatewayHttpClient.Response(
                HttpStatus.OK, "leaked: klt_AbCdEf123456789012345");

        CallToolResult result = McpErrorMapper.toResult(response);

        assertThat(((TextContent) result.content().get(0)).text())
                .contains("klt_***REDACTED***")
                .doesNotContain("klt_AbCdEf123456789012345");
    }

    @Test
    void redactionScrubsPatTokensFromErrorBody() {
        GatewayHttpClient.Response response = new GatewayHttpClient.Response(
                HttpStatus.UNAUTHORIZED,
                "{\"message\":\"invalid token klt_topsecret999999999999999\"}");

        CallToolResult result = McpErrorMapper.toResult(response);

        String text = ((TextContent) result.content().get(0)).text();
        assertThat(text)
                .contains("klt_***REDACTED***")
                .doesNotContain("klt_topsecret");
    }

    @Test
    void redactionScrubsPatTokensFromExceptionMessage() {
        CallToolResult result = McpErrorMapper.fromException(
                new RuntimeException("auth failed for klt_DEADBEEF1234567890123456"));

        assertThat(((TextContent) result.content().get(0)).text())
                .contains("klt_***REDACTED***")
                .doesNotContain("klt_DEADBEEF");
    }

    @Test
    void redactionLeavesNonPatStringsAlone() {
        assertThat(McpErrorMapper.redact("normal text with no token"))
                .isEqualTo("normal text with no token");
        assertThat(McpErrorMapper.redact(null)).isNull();
    }

    @Test
    void successHasNoStructuredContent() {
        GatewayHttpClient.Response response = new GatewayHttpClient.Response(
                HttpStatus.OK, "{\"data\":[1,2,3]}");

        CallToolResult result = McpErrorMapper.toResult(response);

        assertThat(result.structuredContent()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void nonSuccessCarriesStructuredErrorsVerbatim() {
        GatewayHttpClient.Response response = new GatewayHttpClient.Response(
                HttpStatus.BAD_REQUEST,
                "{\"errors\":[{\"status\":\"400\",\"code\":\"VALIDATION_FAILED\","
                        + "\"title\":\"Validation Error\",\"detail\":\"name must not be blank\","
                        + "\"source\":{\"pointer\":\"/data/attributes/name\"}}]}");

        CallToolResult result = McpErrorMapper.toResult(response);

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured.get("status")).isEqualTo(400);
        List<Object> errors = (List<Object>) structured.get("errors");
        assertThat(errors).hasSize(1);
        Map<String, Object> firstError = (Map<String, Object>) errors.get(0);
        assertThat(firstError.get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat((Map<String, Object>) firstError.get("source"))
                .containsEntry("pointer", "/data/attributes/name");
        // Text content is unchanged alongside the new structuredContent.
        assertThat(((TextContent) result.content().get(0)).text()).contains("VALIDATION_FAILED");
    }

    @Test
    void nonParseableBodyYieldsEmptyStructuredErrors() {
        GatewayHttpClient.Response response = new GatewayHttpClient.Response(
                HttpStatus.INTERNAL_SERVER_ERROR, "boom");

        CallToolResult result = McpErrorMapper.toResult(response);

        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured.get("status")).isEqualTo(500);
        assertThat((List<?>) structured.get("errors")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void structuredErrorsAreRedactedLikeTextContent() {
        GatewayHttpClient.Response response = new GatewayHttpClient.Response(
                HttpStatus.UNAUTHORIZED,
                "{\"errors\":[{\"detail\":\"invalid token klt_topsecret999999999999999\"}]}");

        CallToolResult result = McpErrorMapper.toResult(response);

        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        List<Object> errors = (List<Object>) structured.get("errors");
        Map<String, Object> firstError = (Map<String, Object>) errors.get(0);
        assertThat((String) firstError.get("detail"))
                .contains("klt_***REDACTED***")
                .doesNotContain("klt_topsecret");
    }
}
