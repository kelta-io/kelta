package io.kelta.gateway.filter;

import io.kelta.gateway.geo.ClientIpResolver;
import io.kelta.gateway.ratelimit.RateLimitExemptionService;
import io.kelta.gateway.ratelimit.RateLimitResult;
import io.kelta.gateway.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for IpRateLimitFilter.
 *
 * Tests verify that the filter:
 * - Rate-limits only the configured public path prefixes (longest-prefix match)
 * - Delegates counting to the shared Redis window, keyed per prefix and per IP
 * - Honours the CIDR exemption list without spending a Redis round trip
 * - Returns 429 with an honest Retry-After when a budget is exhausted
 * - Resolves client IPs from headers and remote address
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IpRateLimitFilter Tests")
class IpRateLimitFilterTest {

    /** Small budgets keep the expectations readable. */
    private static final List<String> PATHS =
            List.of("/actuator/health=3", "/api/modules/webhooks=5");

    @Mock
    private RedisRateLimiter rateLimiter;

    @Mock
    private GatewayFilterChain chain;

    private IpRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = newFilter(List.of());
        lenient().when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    private IpRateLimitFilter newFilter(List<String> exemptCidrs) {
        ClientIpResolver resolver = new ClientIpResolver(true);
        return new IpRateLimitFilter(
                resolver, new RateLimitExemptionService(resolver, exemptCidrs), rateLimiter, PATHS);
    }

    private void givenWindow(RateLimitResult result) {
        when(rateLimiter.checkWindow(anyString(), anyLong(), any(Duration.class)))
                .thenReturn(Mono.just(result));
    }

    @Nested
    @DisplayName("Path matching")
    class PathMatching {

        @Test
        @DisplayName("should rate-limit /actuator/health")
        void shouldRateLimitHealthPath() {
            assertThat(filter.matchPath("/actuator/health")).isEqualTo("/actuator/health");
        }

        @Test
        @DisplayName("should match a configured prefix against a deeper sub-path")
        void shouldMatchPrefixAgainstSubPath() {
            // The regression this guards: exact Set membership would miss the real
            // webhook URL, silently leaving it unlimited.
            assertThat(filter.matchPath("/api/modules/webhooks/stripe/tenant-123"))
                    .isEqualTo("/api/modules/webhooks");
        }

        @Test
        @DisplayName("should not rate-limit authenticated API paths")
        void shouldNotRateLimitApiPaths() {
            assertThat(filter.matchPath("/api/collections")).isNull();
        }

        @Test
        @DisplayName("should not rate-limit actuator metrics paths")
        void shouldNotRateLimitActuatorMetricsPaths() {
            assertThat(filter.matchPath("/actuator/metrics")).isNull();
        }

        @Test
        @DisplayName("should not rate-limit internal paths")
        void shouldNotRateLimitInternalPaths() {
            assertThat(filter.matchPath("/internal/bootstrap")).isNull();
        }

        @Test
        @DisplayName("should prefer the longest matching prefix")
        void shouldPreferLongestPrefix() {
            Map<String, Integer> budgets = IpRateLimitFilter.parsePathBudgets(
                    List.of("/api/modules=10", "/api/modules/webhooks=300"));
            ClientIpResolver resolver = new ClientIpResolver(true);
            IpRateLimitFilter f = new IpRateLimitFilter(
                    resolver,
                    new RateLimitExemptionService(resolver, List.of()),
                    rateLimiter,
                    List.of("/api/modules=10", "/api/modules/webhooks=300"));
            assertThat(budgets).containsEntry("/api/modules/webhooks", 300);
            assertThat(f.matchPath("/api/modules/webhooks/stripe/t1"))
                    .isEqualTo("/api/modules/webhooks");
            // A sibling under the shorter prefix still matches the shorter budget.
            assertThat(f.matchPath("/api/modules/install-jar")).isEqualTo("/api/modules");
        }

        @Test
        @DisplayName("should skip malformed budget entries rather than fail")
        void shouldSkipMalformedEntries() {
            Map<String, Integer> budgets = IpRateLimitFilter.parsePathBudgets(
                    List.of("/good=5", "=10", "/bad=zero", "/nonpositive=0", "  "));
            assertThat(budgets).containsEntry("/good", 5);
            assertThat(budgets).containsKey("/bad"); // falls back to the default budget
            assertThat(budgets).doesNotContainKey("/nonpositive");
            assertThat(budgets).doesNotContainKey("");
        }

        @Test
        @DisplayName("defaults should not claim to protect /portal, which never transits the gateway")
        void defaultsShouldNotIncludePortalPaths() {
            // /portal/** is served by kelta-auth's own ingress, so an entry here
            // would read as protection that can never fire.
            //
            // Asserted as "no key starts with /portal" rather than as an exhaustive key list.
            // The exhaustive form (which also carried a duplicated key) failed every time a
            // legitimate public path was added, producing a failure that named portal paths
            // while actually objecting to something unrelated.
            Map<String, Integer> defaults = IpRateLimitFilter.parsePathBudgets(
                    List.of(IpRateLimitFilter.DEFAULT_IP_PATHS.split(",")));
            assertThat(defaults).isNotEmpty();
            assertThat(defaults.keySet()).noneMatch(path -> path.startsWith("/portal"));
        }
    }

    @Nested
    @DisplayName("Default budgets")
    class DefaultBudgets {

        private IpRateLimitFilter defaultsFilter() {
            ClientIpResolver resolver = new ClientIpResolver(true);
            return new IpRateLimitFilter(resolver, new RateLimitExemptionService(resolver, List.of()),
                    rateLimiter, List.of(IpRateLimitFilter.DEFAULT_IP_PATHS.split(",")));
        }

        @Test
        @DisplayName("application.yml fallback should equal DEFAULT_IP_PATHS")
        void applicationYmlFallbackShouldEqualDefault() {
            YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
            // The shipped file, not the classpath copy: src/test/resources/application.yml
            // shadows it on the test classpath. Surefire runs with the module directory as cwd.
            factory.setResources(new FileSystemResource("src/main/resources/application.yml"));
            factory.afterPropertiesSet();
            Properties props = factory.getObject();
            assertThat(props).isNotNull();

            String value = props.getProperty("kelta.gateway.rate-limit.ip-paths");
            String prefix = "${RATE_LIMIT_IP_PATHS:";
            assertThat(value).startsWith(prefix).endsWith("}");
            assertThat(value.substring(prefix.length(), value.length() - 1))
                    .isEqualTo(IpRateLimitFilter.DEFAULT_IP_PATHS);
        }

        @Test
        @DisplayName("defaults should limit flow webhooks while mail and SES keep their own buckets")
        void defaultsShouldBucketWebhookPrefixesSeparately() {
            IpRateLimitFilter f = defaultsFilter();
            Map<String, Integer> defaults = IpRateLimitFilter.parsePathBudgets(
                    List.of(IpRateLimitFilter.DEFAULT_IP_PATHS.split(",")));

            assertThat(f.matchPath("/api/webhooks/3f2b8c1e-7d4a-4e6b-9a0c-5d1e2f3a4b5c"))
                    .isEqualTo("/api/webhooks");
            assertThat(f.matchPath("/api/webhooks/mail/inbound")).isEqualTo("/api/webhooks/mail");
            assertThat(f.matchPath("/api/webhooks/ses/notifications")).isEqualTo("/api/webhooks/ses");
            assertThat(defaults)
                    .containsEntry("/api/webhooks", 300)
                    .containsEntry("/api/webhooks/mail", 600)
                    .containsEntry("/api/webhooks/ses", 600);
        }

        @Test
        @DisplayName("should 429 one IP over the flow-webhook budget while another IP passes")
        void shouldLimitFlowWebhookPerIp() {
            IpRateLimitFilter f = defaultsFilter();
            String path = "/api/webhooks/3f2b8c1e-7d4a-4e6b-9a0c-5d1e2f3a4b5c";
            when(rateLimiter.checkWindow(eq("ratelimit:ip:/api/webhooks:198.51.100.1"), eq(300L),
                    any(Duration.class)))
                    .thenReturn(Mono.just(RateLimitResult.allowed(0)),
                            Mono.just(RateLimitResult.notAllowed(Duration.ofSeconds(42))));
            when(rateLimiter.checkWindow(eq("ratelimit:ip:/api/webhooks:198.51.100.2"), eq(300L),
                    any(Duration.class)))
                    .thenReturn(Mono.just(RateLimitResult.allowed(299)));

            MockServerWebExchange lastAllowed = createPost(path, "198.51.100.1");
            StepVerifier.create(f.filter(lastAllowed, chain)).verifyComplete();
            verify(chain).filter(lastAllowed);

            MockServerWebExchange overBudget = createPost(path, "198.51.100.1");
            StepVerifier.create(f.filter(overBudget, chain)).verifyComplete();
            assertThat(overBudget.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(overBudget.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("42");
            StepVerifier.create(overBudget.getResponse().getBodyAsString())
                    .assertNext(body -> assertThat(body).startsWith(
                            "{\"error\":{\"status\":429,\"code\":\"TOO_MANY_REQUESTS\","))
                    .verifyComplete();
            verify(chain, never()).filter(overBudget);

            MockServerWebExchange otherIp = createPost(path, "198.51.100.2");
            StepVerifier.create(f.filter(otherIp, chain)).verifyComplete();
            verify(chain).filter(otherIp);
            assertThat(otherIp.getResponse().getStatusCode())
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Nested
    @DisplayName("Redis-backed window")
    class RedisBackedWindow {

        @Test
        @DisplayName("should pass the request down the chain when the window allows it")
        void shouldAllowRequestWithinBudget() {
            givenWindow(RateLimitResult.allowed(2));
            MockServerWebExchange exchange = createExchange("/actuator/health", "192.168.1.1");

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            verify(chain).filter(exchange);
            assertThat(exchange.getResponse().getStatusCode())
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("should return 429 with the window's real remaining TTL as Retry-After")
        void shouldReturn429WithHonestRetryAfter() {
            // Retry-After must be what Redis reports is left of the window, not the
            // full window — a client told to wait 60s after 43s have elapsed backs
            // off for nearly two windows.
            givenWindow(RateLimitResult.notAllowed(Duration.ofSeconds(17)));
            MockServerWebExchange exchange = createExchange("/actuator/health", "10.0.0.1");

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("17");
            assertThat(exchange.getResponse().getHeaders().getFirst("Content-Type"))
                    .isEqualTo("application/json");
            verify(chain, never()).filter(any(ServerWebExchange.class));
        }

        @Test
        @DisplayName("should floor Retry-After at one second")
        void shouldFloorRetryAfterAtOneSecond() {
            // A sub-second TTL rounds to 0, and "Retry-After: 0" invites an
            // immediate retry that is guaranteed to be rejected again.
            givenWindow(RateLimitResult.notAllowed(Duration.ofMillis(200)));
            MockServerWebExchange exchange = createExchange("/actuator/health", "10.0.0.2");

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        }

        @Test
        @DisplayName("should key the window by matched prefix and client IP")
        void shouldKeyWindowByPrefixAndClientIp() {
            // This key IS the multi-replica fix: every gateway pod must increment
            // the same counter, so the shape is asserted exactly.
            givenWindow(RateLimitResult.allowed(4));
            MockServerWebExchange exchange =
                    createExchange("/api/modules/webhooks/stripe/tenant-1", "203.0.113.9");

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> limitCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Duration> windowCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(rateLimiter).checkWindow(keyCaptor.capture(), limitCaptor.capture(),
                    windowCaptor.capture());

            assertThat(keyCaptor.getValue())
                    .isEqualTo("ratelimit:ip:/api/modules/webhooks:203.0.113.9");
            assertThat(limitCaptor.getValue()).isEqualTo(5L);
            assertThat(windowCaptor.getValue()).isEqualTo(IpRateLimitFilter.WINDOW);
        }

        @Test
        @DisplayName("should give each matched prefix its own key for the same IP")
        void shouldUseDistinctKeysPerPrefix() {
            // One public endpoint's burst must not spend another endpoint's budget.
            givenWindow(RateLimitResult.allowed(1));
            String clientIp = "10.0.0.7";

            StepVerifier.create(filter.filter(createExchange("/actuator/health", clientIp), chain))
                    .verifyComplete();
            StepVerifier.create(filter.filter(
                            createExchange("/api/modules/webhooks/stripe/t1", clientIp), chain))
                    .verifyComplete();

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimiter, times(2))
                    .checkWindow(keyCaptor.capture(), anyLong(), any(Duration.class));
            assertThat(keyCaptor.getAllValues()).containsExactly(
                    "ratelimit:ip:/actuator/health:10.0.0.7",
                    "ratelimit:ip:/api/modules/webhooks:10.0.0.7");
        }

        @Test
        @DisplayName("should give each client IP its own key for the same prefix")
        void shouldUseDistinctKeysPerClientIp() {
            givenWindow(RateLimitResult.allowed(1));

            StepVerifier.create(filter.filter(createExchange("/actuator/health", "10.0.0.1"), chain))
                    .verifyComplete();
            StepVerifier.create(filter.filter(createExchange("/actuator/health", "10.0.0.2"), chain))
                    .verifyComplete();

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimiter, times(2))
                    .checkWindow(keyCaptor.capture(), anyLong(), any(Duration.class));
            assertThat(keyCaptor.getAllValues()).containsExactly(
                    "ratelimit:ip:/actuator/health:10.0.0.1",
                    "ratelimit:ip:/actuator/health:10.0.0.2");
        }

        @Test
        @DisplayName("should not touch Redis for a path that is not rate limited")
        void shouldPassThroughNonRateLimitedPaths() {
            MockServerWebExchange exchange = createExchangeWithRemoteAddress("/api/collections");

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            verify(chain).filter(exchange);
            verify(rateLimiter, never()).checkWindow(anyString(), anyLong(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("CIDR exemption")
    class Exemption {

        @Test
        @DisplayName("should short-circuit an exempt IP without a Redis round trip")
        void shouldNotLimitExemptRange() {
            IpRateLimitFilter exempting = newFilter(List.of("10.0.0.0/8"));

            for (int i = 0; i < 10; i++) {
                MockServerWebExchange exchange = createExchange("/actuator/health", "10.1.2.3");
                StepVerifier.create(exempting.filter(exchange, chain)).verifyComplete();
                assertThat(exchange.getResponse().getStatusCode())
                        .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            }
            verify(rateLimiter, never()).checkWindow(anyString(), anyLong(), any(Duration.class));
        }

        @Test
        @DisplayName("should still limit an IP outside the exempt range")
        void shouldLimitNonExemptRange() {
            givenWindow(RateLimitResult.notAllowed(Duration.ofSeconds(30)));
            IpRateLimitFilter exempting = newFilter(List.of("10.0.0.0/8"));

            MockServerWebExchange exceeded = createExchange("/actuator/health", "192.0.2.5");
            StepVerifier.create(exempting.filter(exceeded, chain)).verifyComplete();

            assertThat(exceeded.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("should accept a bare address as a single-host exemption")
        void shouldAcceptBareAddress() {
            givenWindow(RateLimitResult.allowed(2));
            IpRateLimitFilter exempting = newFilter(List.of("203.0.113.7"));

            for (int i = 0; i < 10; i++) {
                StepVerifier.create(
                                exempting.filter(createExchange("/actuator/health", "203.0.113.7"), chain))
                        .verifyComplete();
            }
            MockServerWebExchange neighbour = createExchange("/actuator/health", "203.0.113.8");
            StepVerifier.create(exempting.filter(neighbour, chain)).verifyComplete();

            assertThat(neighbour.getResponse().getStatusCode())
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            // Only the neighbour was counted; the exempt host never reached Redis.
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimiter).checkWindow(keyCaptor.capture(), anyLong(), any(Duration.class));
            assertThat(keyCaptor.getValue()).isEqualTo("ratelimit:ip:/actuator/health:203.0.113.8");
        }
    }

    @Nested
    @DisplayName("Client IP resolution")
    class ClientIpResolution {

        @Test
        @DisplayName("should use X-Forwarded-For header when present")
        void shouldUseXForwardedForHeader() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/actuator/health")
                    .header("X-Forwarded-For", "203.0.113.50, 70.41.3.18, 150.172.238.178")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            String ip = filter.resolveClientIp(exchange);
            assertThat(ip).isEqualTo("203.0.113.50");
        }

        @Test
        @DisplayName("should fall back to remote address when no X-Forwarded-For")
        void shouldFallBackToRemoteAddress() {
            MockServerWebExchange exchange = createExchangeWithRemoteAddress("/actuator/health");
            String ip = filter.resolveClientIp(exchange);
            // MockServerHttpRequest uses localhost by default
            assertThat(ip).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Filter ordering")
    class FilterOrdering {

        @Test
        @DisplayName("should have order -150 (before JWT filter)")
        void shouldHaveCorrectOrder() {
            assertThat(filter.getOrder()).isEqualTo(-150);
        }
    }

    /**
     * Creates a MockServerWebExchange with an X-Forwarded-For header for the given IP.
     */
    private MockServerWebExchange createExchange(String path, String clientIp) {
        MockServerHttpRequest request = MockServerHttpRequest
                .get(path)
                .header("X-Forwarded-For", clientIp)
                .build();
        return MockServerWebExchange.from(request);
    }

    private MockServerWebExchange createPost(String path, String clientIp) {
        MockServerHttpRequest request = MockServerHttpRequest
                .post(path)
                .header("X-Forwarded-For", clientIp)
                .body("{}");
        return MockServerWebExchange.from(request);
    }

    /**
     * Creates a MockServerWebExchange using the default remote address (no X-Forwarded-For).
     */
    private MockServerWebExchange createExchangeWithRemoteAddress(String path) {
        MockServerHttpRequest request = MockServerHttpRequest
                .get(path)
                .build();
        return MockServerWebExchange.from(request);
    }
}
