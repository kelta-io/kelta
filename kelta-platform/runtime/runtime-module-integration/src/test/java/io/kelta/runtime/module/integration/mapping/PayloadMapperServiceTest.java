package io.kelta.runtime.module.integration.mapping;

import io.kelta.runtime.flow.StateDataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PayloadMapperService")
class PayloadMapperServiceTest {

    private PayloadMapperService mapper;

    @BeforeEach
    void setUp() {
        mapper = new PayloadMapperService(new StateDataResolver(new ObjectMapper()));
    }

    @Test
    @DisplayName("Substitutes ${$.path} placeholders unchanged from the existing engine")
    void preservesDollarPathSyntax() {
        Map<String, Object> state = Map.of(
            "record", Map.of("data", Map.of("orderId", "O-100")));

        @SuppressWarnings("unchecked")
        Map<String, Object> resolved = (Map<String, Object>) mapper.map(
            Map.of("orderId", "${$.record.data.orderId}"), state);

        assertEquals("O-100", resolved.get("orderId"));
    }

    @Test
    @DisplayName("Evaluates strings starting with = as JSONata expressions")
    void evaluatesEqualsExpression() {
        Map<String, Object> state = Map.of(
            "order", Map.of(
                "items", List.of(
                    Map.of("name", "Widget", "price", 10),
                    Map.of("name", "Gadget", "price", 5))));

        @SuppressWarnings("unchecked")
        Map<String, Object> resolved = (Map<String, Object>) mapper.map(
            Map.of("total", "=$sum(order.items.price)"), state);

        // jsonata returns Number; assertion is permissive on int vs long.
        assertEquals(15, ((Number) resolved.get("total")).intValue());
    }

    @Test
    @DisplayName("Treats { $expr: ... } objects as full-document expressions")
    void evaluatesDollarExprObject() {
        Map<String, Object> state = Map.of("name", "Buddy", "status", "available");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) mapper.map(
            Map.of("$expr", "{ \"upper\": $uppercase(name), \"isAvailable\": status = 'available' }"),
            state);

        assertEquals("BUDDY", result.get("upper"));
        assertEquals(true, result.get("isAvailable"));
    }

    @Test
    @DisplayName("Recurses into nested objects and arrays")
    void recurses() {
        Map<String, Object> state = Map.of("user", Map.of("name", "Alex"));

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("greeting", "Hello ${$.user.name}");
        template.put("tags", List.of("a", "${$.user.name}"));
        template.put("nested", Map.of("who", "${$.user.name}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) mapper.map(template, state);

        assertEquals("Hello Alex", out.get("greeting"));
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) out.get("tags");
        assertEquals(List.of("a", "Alex"), tags);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) out.get("nested");
        assertEquals("Alex", nested.get("who"));
    }

    @Test
    @DisplayName("Surfaces JSONata errors as PayloadMapperException")
    void mapperException() {
        assertThrows(PayloadMapperException.class, () ->
            mapper.map(Map.of("x", "=this is not valid jsonata at all $$"), Map.of()));
    }

    @Test
    @DisplayName("Returns null when template is null")
    void nullTemplate() {
        assertNull(mapper.map(null, Map.of()));
    }

    @Test
    @DisplayName("mapToObject throws when expression yields a scalar")
    void mapToObjectRejectsScalar() {
        assertThrows(PayloadMapperException.class, () ->
            mapper.mapToObject("=42", Map.of()));
    }

    @Test
    @DisplayName("JSONata sees a java.time.Instant in state as its ISO-8601 string")
    void jsonataReadsInstantAsIsoString() {
        Instant createdAt = Instant.parse("2026-10-25T05:54:15.061833Z");
        Map<String, Object> state = Map.of("record", Map.of("createdAt", createdAt));

        assertEquals("2026-10-25T05:54:15.061833Z", mapper.map("=$.record.createdAt", state));
    }

    @Test
    @DisplayName("JSONata sees dates, decimals and UUIDs as JSON strings and numbers")
    void jsonataReadsNonJsonTypes() {
        UUID id = UUID.fromString("76d4fa27-63b1-436e-a311-fda842537664");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("updatedAt", OffsetDateTime.of(2026, 10, 25, 6, 0, 0, 0, ZoneOffset.ofHours(-6)));
        record.put("dueOn", LocalDate.of(2026, 11, 1));
        record.put("amount", new BigDecimal("12.50"));
        record.put("id", id);
        record.put("tags", List.of(Instant.parse("2026-01-01T00:00:00Z")));
        Map<String, Object> state = Map.of("record", record);

        assertEquals("2026-10-25T06:00-06:00", mapper.map("=record.updatedAt", state));
        assertEquals("2026-11-01", mapper.map("=record.dueOn", state));
        assertEquals(25.0, ((Number) mapper.map("=record.amount * 2", state)).doubleValue());
        assertEquals(id.toString(), mapper.map("=record.id", state));
        assertEquals("2026-01-01T00:00:00Z", mapper.map("=record.tags[0]", state));
    }
}
