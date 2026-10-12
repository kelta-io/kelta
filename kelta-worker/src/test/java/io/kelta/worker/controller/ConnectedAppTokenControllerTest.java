package io.kelta.worker.controller;

import io.kelta.worker.repository.ConnectedAppRepository;
import io.kelta.worker.util.WithTenant;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ConnectedAppTokenController")
@WithTenant("t1")
class ConnectedAppTokenControllerTest {

    private ConnectedAppRepository repository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ConnectedAppTokenController controller;

    @BeforeEach
    void setUp() {
        repository = mock(ConnectedAppRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        controller = new ConnectedAppTokenController(repository, redisTemplate, "http://localhost:8080", 10);
    }

    @Nested
    @DisplayName("listTokens")
    class ListTokens {

        @Test
        @DisplayName("Should list tokens for tenant-scoped app")
        void shouldListTokens() {
            when(repository.findByIdAndTenant("app-1", "t1"))
                    .thenReturn(Optional.of(Map.of("id", "app-1")));
            when(repository.findTokensByAppId("app-1"))
                    .thenReturn(List.of(Map.of("id", "tok-1", "scopes", "read:contacts")));

            var response = controller.listTokens("app-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should return 404 for other tenant's app")
        void shouldReturn404ForOtherTenant() {
            when(repository.findByIdAndTenant("app-other", "t1")).thenReturn(Optional.empty());

            var response = controller.listTokens("app-other");

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("generateToken")
    class GenerateToken {

        @Test
        @DisplayName("Returns 410 pointing at the client_credentials flow — never writes a placeholder row")
        void returnsGoneWithGuidance() {
            when(repository.findByIdAndTenant("app-1", "t1"))
                    .thenReturn(Optional.of(Map.of("id", "app-1", "client_id", "cid-1", "scopes", "read:all")));

            var response = controller.generateToken("app-1");

            assertThat(response.getStatusCode().value()).isEqualTo(410);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body.get("clientId")).isEqualTo("cid-1");
            assertThat((String) body.get("message")).contains("client_credentials");
        }

        @Test
        @DisplayName("Unknown app → 404")
        void unknownAppNotFound() {
            when(repository.findByIdAndTenant("app-x", "t1")).thenReturn(Optional.empty());

            var response = controller.generateToken("app-x");

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("revokeToken")
    class RevokeToken {

        @Test
        @DisplayName("Should revoke token and add to Redis revocation set")
        void shouldRevokeAndAddToRedis() {
            when(repository.findByIdAndTenant("app-1", "t1"))
                    .thenReturn(Optional.of(Map.of("id", "app-1")));
            when(repository.revokeToken("tok-1", "app-1")).thenReturn(1);

            var response = controller.revokeToken("app-1", "tok-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(valueOps).set(eq("revoked_token:tok-1"), eq("revoked"), any(Duration.class));
            verify(repository).insertAuditRecord(eq("t1"), eq("app-1"), eq("TOKEN_REVOKED"), anyString(), any());
        }

        @Test
        @DisplayName("Should return 404 for nonexistent token")
        void shouldReturn404ForMissingToken() {
            when(repository.findByIdAndTenant("app-1", "t1"))
                    .thenReturn(Optional.of(Map.of("id", "app-1")));
            when(repository.revokeToken("tok-missing", "app-1")).thenReturn(0);

            var response = controller.revokeToken("app-1", "tok-missing");

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("getAuditTrail")
    class GetAuditTrail {

        @Test
        @DisplayName("Should return audit trail for tenant-scoped app")
        void shouldReturnAuditTrail() {
            when(repository.findByIdAndTenant("app-1", "t1"))
                    .thenReturn(Optional.of(Map.of("id", "app-1")));
            when(repository.findAuditByAppId("app-1", 50))
                    .thenReturn(List.of(Map.of("action", "TOKEN_GENERATED")));

            var response = controller.getAuditTrail("app-1", 50);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }
}
