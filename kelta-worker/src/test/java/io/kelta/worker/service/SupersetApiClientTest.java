package io.kelta.worker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SupersetApiClient")
class SupersetApiClientTest {

    private RestTemplate restTemplate;
    private SupersetApiClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new SupersetApiClient("http://superset:8088", "admin", "password", restTemplate,
                new SupersetDatabaseTarget("db.internal", 5432, "kelta"));
    }

    private void mockAuthentication() {
        var loginBody = new Object() {
            @SuppressWarnings("unused")
            public String access_token = "test-token";
        };
        when(restTemplate.exchange(
                eq("http://superset:8088/api/v1/security/login"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(Class.class)
        )).thenReturn(ResponseEntity.ok(Map.of("access_token", "test-token")));
    }

    @Test
    @DisplayName("health check calls /health endpoint")
    void healthCheck() {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("OK"));

        assertDoesNotThrow(() -> client.healthCheck());
        verify(restTemplate).getForEntity("http://superset:8088/health", String.class);
    }

    @Test
    @DisplayName("generates guest token with dashboard ID and user")
    void generateGuestToken() {
        mockAuthentication();

        when(restTemplate.exchange(
                contains("/api/v1/security/guest_token/"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(Class.class)
        )).thenReturn(ResponseEntity.ok(Map.of("token", "guest-token-abc")));

        String token = client.generateGuestToken(
                "dashboard-uuid",
                Map.of("username", "test@example.com"),
                List.of(Map.of("clause", "1=1"))
        );

        assertEquals("guest-token-abc", token);
    }

    @Test
    @DisplayName("returns null when guest token generation fails")
    void generateGuestTokenFailure() {
        mockAuthentication();

        when(restTemplate.exchange(
                contains("/api/v1/security/guest_token/"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(Class.class)
        )).thenThrow(new RuntimeException("Server error"));

        String token = client.generateGuestToken(
                "dashboard-uuid",
                Map.of("username", "test@example.com"),
                List.of()
        );

        assertNull(token);
    }

    @Test
    @DisplayName("creates database connection with per-tenant PostgreSQL user")
    void createDatabaseConnection() {
        mockAuthentication();

        when(restTemplate.exchange(
                contains("/api/v1/database/"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(Class.class)
        )).thenReturn(ResponseEntity.ok(Map.of("id", 42)));

        int dbId = client.createDatabaseConnection("tenant-uuid", "acme",
                "superset_acme", "generated-pass");

        assertEquals(42, dbId);
    }

    @Test
    @DisplayName("points the tenant connection at the configured database target")
    @SuppressWarnings("unchecked")
    void createDatabaseConnectionUsesTarget() {
        mockAuthentication();
        var captor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(
                contains("/api/v1/database/"),
                eq(HttpMethod.POST),
                captor.capture(),
                any(Class.class)
        )).thenReturn(ResponseEntity.ok(Map.of("id", 42)));

        client.createDatabaseConnection("tenant-uuid", "acme", "superset_acme", "generated-pass");

        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        assertEquals("postgresql://superset_acme:generated-pass@db.internal:5432/kelta",
                body.get("sqlalchemy_uri"));
        assertEquals("kelta-acme", body.get("database_name"));
    }

    @Test
    @DisplayName("returns -1 when database creation fails")
    void createDatabaseConnectionFailure() {
        mockAuthentication();

        when(restTemplate.exchange(
                contains("/api/v1/database/"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(Class.class)
        )).thenThrow(new RuntimeException("Conflict"));

        int dbId = client.createDatabaseConnection("tenant-uuid", "acme",
                "superset_acme", "generated-pass");

        assertEquals(-1, dbId);
    }
}
