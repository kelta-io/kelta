package io.kelta.auth.filter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PortalPublicRateLimitFilter Tests")
class PortalPublicRateLimitFilterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private FilterChain chain;

    private PortalPublicRateLimitFilter filter(int trustedProxies) {
        return new PortalPublicRateLimitFilter(redisTemplate,
                List.of(PortalPublicRateLimitFilter.DEFAULT_IP_PATHS.split(",")), trustedProxies, List.of());
    }

    private PortalPublicRateLimitFilter trustingProxies(String... cidrs) {
        return new PortalPublicRateLimitFilter(redisTemplate,
                List.of(PortalPublicRateLimitFilter.DEFAULT_IP_PATHS.split(",")), 1, List.of(cidrs));
    }

    @SuppressWarnings("unchecked")
    private void givenWindowCount(long count) {
        lenient().when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(count);
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("10.0.0.9");
        return request;
    }

    @Nested
    @DisplayName("Enforcement")
    class Enforcement {

        @Test
        @DisplayName("passes a request that is within budget")
        void allowsWithinBudget() throws Exception {
            givenWindowCount(1L);
            var response = new MockHttpServletResponse();

            filter(1).doFilter(request("/portal/api/signup"), response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("blocks the request that exceeds the budget — the chain never runs")
        void blocksOverBudget() throws Exception {
            // Rejecting after the handler ran would still have sent the email.
            givenWindowCount(6L); // signup budget is 5
            var response = new MockHttpServletResponse();
            when(redisTemplate.getExpire(anyString())).thenReturn(17L);

            filter(1).doFilter(request("/portal/api/signup"), response, chain);

            verify(chain, never()).doFilter(any(), any());
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("17");
            assertThat(response.getContentAsString()).contains("TOO_MANY_REQUESTS");
        }

        @Test
        @DisplayName("Retry-After falls back to the window when Redis has no TTL")
        void retryAfterFallsBack() throws Exception {
            givenWindowCount(99L);
            when(redisTemplate.getExpire(anyString())).thenReturn(-1L);
            var response = new MockHttpServletResponse();

            filter(1).doFilter(request("/portal/api/signup"), response, chain);

            assertThat(response.getHeader("Retry-After"))
                    .isEqualTo(String.valueOf(PortalPublicRateLimitFilter.WINDOW.toSeconds()));
        }

        @Test
        @DisplayName("leaves unlisted paths alone without touching Redis")
        void ignoresUnlistedPaths() throws Exception {
            var response = new MockHttpServletResponse();

            filter(1).doFilter(request("/oauth2/authorize"), response, chain);

            verify(chain).doFilter(any(), any());
            verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
        }

        @Test
        @DisplayName("fails OPEN when Redis is unavailable")
        void failsOpen() throws Exception {
            // Losing the cache must not take signup down; the bot challenge and
            // the per-email budget still apply.
            when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));
            var response = new MockHttpServletResponse();

            filter(1).doFilter(request("/portal/api/signup"), response, chain);

            verify(chain).doFilter(any(), any());
        }
    }

    @Nested
    @DisplayName("Bucketing")
    class Bucketing {

        @SuppressWarnings("unchecked")
        private String keyFor(String uri, String forwardedFor) throws Exception {
            givenWindowCount(1L);
            MockHttpServletRequest request = request(uri);
            if (forwardedFor != null) {
                request.addHeader("X-Forwarded-For", forwardedFor);
            }
            filter(1).doFilter(request, new MockHttpServletResponse(), chain);

            ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
            verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), anyString());
            return keys.getValue().getFirst();
        }

        @Test
        @DisplayName("gives each path its own bucket per IP")
        void perPathPerIpBuckets() throws Exception {
            // Otherwise a burst of challenge fetches would spend the signup budget.
            assertThat(keyFor("/portal/api/signup", null))
                    .isEqualTo("authratelimit:ip:/portal/api/signup:10.0.0.9");

            org.mockito.Mockito.reset(redisTemplate);
            assertThat(keyFor("/portal/api/challenge", null))
                    .isEqualTo("authratelimit:ip:/portal/api/challenge:10.0.0.9");
        }

        @Test
        @DisplayName("matches by longest prefix so a sub-path shares its parent's budget")
        void matchesLongestPrefix() {
            var f = filter(1);
            assertThat(f.matchPath("/portal/api/login/request")).isEqualTo("/portal/api/login/request");
            assertThat(f.matchPath("/portal/api/login/verify")).isNull();
            assertThat(f.matchPath("/portal/api/signup")).isEqualTo("/portal/api/signup");
        }
    }

    @Nested
    @DisplayName("Client IP resolution")
    class ClientIpResolution {

        @Test
        @DisplayName("takes the hop the trusted proxy appended, not the client's own claim")
        void usesTrustedHop() {
            // The leftmost entry is attacker-controlled: honouring it lets anyone
            // mint a fresh bucket per request by varying one header, which makes
            // the limiter decorative.
            MockHttpServletRequest request = request("/portal/api/signup");
            request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");

            assertThat(filter(1).resolveClientIp(request)).isEqualTo("203.0.113.7");
        }

        @Test
        @DisplayName("counts back the configured number of trusted proxy hops")
        void countsTrustedHops() {
            MockHttpServletRequest request = request("/portal/api/signup");
            request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7, 10.1.1.1");

            assertThat(filter(2).resolveClientIp(request)).isEqualTo("203.0.113.7");
        }

        @Test
        @DisplayName("never indexes past the start of a shorter-than-expected chain")
        void clampsShortChain() {
            MockHttpServletRequest request = request("/portal/api/signup");
            request.addHeader("X-Forwarded-For", "203.0.113.7");

            assertThat(filter(3).resolveClientIp(request)).isEqualTo("203.0.113.7");
        }

        @Test
        @DisplayName("ignores the header entirely when no proxy is trusted")
        void ignoresHeaderWithoutTrustedProxy() {
            // Direct exposure: any forwarded header is pure client input.
            MockHttpServletRequest request = request("/portal/api/signup");
            request.addHeader("X-Forwarded-For", "1.2.3.4");

            assertThat(filter(0).resolveClientIp(request)).isEqualTo("10.0.0.9");
        }

        @Test
        @DisplayName("falls back to the socket address with no forwarded header")
        void fallsBackToRemoteAddr() {
            assertThat(filter(1).resolveClientIp(request("/portal/api/signup")))
                    .isEqualTo("10.0.0.9");
        }
    }

    @Nested
    @DisplayName("Client IP resolution with kelta.security.trusted-proxies")
    class TrustedProxyResolution {

        private MockHttpServletRequest from(String peer, String xff) {
            MockHttpServletRequest request = request("/portal/api/signup");
            request.setRemoteAddr(peer);
            if (xff != null) {
                request.addHeader("X-Forwarded-For", xff);
            }
            return request;
        }

        @Test
        @DisplayName("trusted peer: the right-most untrusted hop, not the client's left-most claim")
        void rightMostUntrustedHop() {
            assertThat(trustingProxies("10.1.0.0/16")
                    .resolveClientIp(from("10.1.0.9", "198.51.100.1, 203.0.113.7, 10.1.0.5")))
                    .isEqualTo("203.0.113.7");
        }

        @Test
        @DisplayName("untrusted peer: forwarded headers are ignored")
        void untrustedPeerIgnoresHeaders() {
            MockHttpServletRequest request = from("192.0.2.4", "198.51.100.1");
            request.addHeader("X-Real-IP", "198.51.100.2");

            assertThat(trustingProxies("10.1.0.0/16").resolveClientIp(request)).isEqualTo("192.0.2.4");
        }

        @Test
        @DisplayName("every hop trusted: the left-most; a malformed hop stops the walk without DNS")
        void allTrustedAndMalformed() {
            PortalPublicRateLimitFilter filter = trustingProxies("10.1.0.0/16");

            assertThat(filter.resolveClientIp(from("10.1.0.9", "10.1.0.3, 10.1.0.5"))).isEqualTo("10.1.0.3");
            assertThat(filter.resolveClientIp(from("10.1.0.9", "203.0.113.7, localhost, 10.1.0.5")))
                    .isEqualTo("10.1.0.5");
        }

        @Test
        @DisplayName("trusted peer without X-Forwarded-For: X-Real-IP, then the peer")
        void realIpThenPeer() {
            PortalPublicRateLimitFilter filter = trustingProxies("10.1.0.9");
            MockHttpServletRequest withRealIp = from("10.1.0.9", null);
            withRealIp.addHeader("X-Real-IP", "203.0.113.8");

            assertThat(filter.resolveClientIp(withRealIp)).isEqualTo("203.0.113.8");
            assertThat(filter.resolveClientIp(from("10.1.0.9", null))).isEqualTo("10.1.0.9");
        }

        @Test
        @DisplayName("an empty list keeps the trusted-proxy-count rule")
        void emptyListKeepsHopCount() {
            assertThat(filter(1).resolveClientIp(from("10.1.0.9", "198.51.100.1, 203.0.113.7")))
                    .isEqualTo("203.0.113.7");
            assertThat(filter(2).resolveClientIp(from("10.1.0.9", "198.51.100.1, 203.0.113.7")))
                    .isEqualTo("198.51.100.1");
        }
    }

    @Nested
    @DisplayName("Configuration parsing")
    class ConfigurationParsing {

        @Test
        @DisplayName("orders entries longest-prefix-first so the specific one wins")
        void ordersLongestFirst() {
            Map<String, Integer> parsed = PortalPublicRateLimitFilter.parsePathBudgets(
                    List.of("/portal=100", "/portal/api/signup=5"));

            assertThat(parsed.keySet()).containsExactly("/portal/api/signup", "/portal");
        }

        @Test
        @DisplayName("skips malformed entries rather than dropping every budget")
        void skipsMalformedEntries() {
            // A typo must not silently disable limiting on the valid entries, and
            // must not stop the service booting either.
            Map<String, Integer> parsed = PortalPublicRateLimitFilter.parsePathBudgets(
                    List.of("/a=5", "=7", "/b=notanumber", "/c=0", "/d=-3", "  ", "/e"));

            assertThat(parsed).containsEntry("/a", 5)
                    .containsEntry("/b", PortalPublicRateLimitFilter.DEFAULT_REQUESTS_PER_WINDOW)
                    .containsEntry("/e", PortalPublicRateLimitFilter.DEFAULT_REQUESTS_PER_WINDOW)
                    .doesNotContainKeys("/c", "/d", "");
        }

        @Test
        @DisplayName("an empty configuration limits nothing")
        void emptyConfigurationLimitsNothing() {
            assertThat(PortalPublicRateLimitFilter.parsePathBudgets(null)).isEmpty();
            assertThat(PortalPublicRateLimitFilter.parsePathBudgets(List.of())).isEmpty();
        }

        @Test
        @DisplayName("ships budgets for every public portal path, form flow included")
        void defaultsCoverPublicPaths() {
            // These are the endpoints self-signup exposes; a default that missed
            // one would leave it unmetered on every deployment. The Thymeleaf
            // /portal/login form matters as much as the JSON API — it sends the
            // same email through the same service.
            Map<String, Integer> parsed = PortalPublicRateLimitFilter.parsePathBudgets(
                    List.of(PortalPublicRateLimitFilter.DEFAULT_IP_PATHS.split(",")));

            assertThat(parsed).containsOnlyKeys("/portal/api/login/request",
                    "/portal/api/challenge", "/portal/api/signup", "/portal/login");
        }

        @Test
        @DisplayName("the form-flow prefix does not swallow the JSON API paths")
        void formPrefixDoesNotShadowApi() {
            // /portal/login and /portal/api/login/request are separate branches;
            // if they ever collapsed, the API would inherit the form's budget.
            var f = filter(1);
            assertThat(f.matchPath("/portal/login/request")).isEqualTo("/portal/login");
            assertThat(f.matchPath("/portal/api/login/request")).isEqualTo("/portal/api/login/request");
        }
    }

    @Nested
    @DisplayName("Window semantics")
    class WindowSemantics {

        @Test
        @DisplayName("sets the window TTL only when the window is new")
        void windowTtlIsNotRefreshed() throws Exception {
            // Refreshing the TTL per request turns the fixed window into an
            // idle-expiry: under sustained traffic the counter never resets and
            // each rejected request extends its own lockout. That is the
            // 2026-07-11 production incident, so the guard is asserted on the
            // script source — a mocked Redis cannot execute it.
            String script = scriptSource();

            assertThat(script).contains("INCR").contains("count == 1").contains("TTL");
            assertThat(script.replaceAll("\\s+", " "))
                    .doesNotContain("redis.call('EXPIRE', KEYS[1], ARGV[1]) return")
                    .contains("if count == 1 or redis.call('TTL', KEYS[1]) < 0 then");
        }

        @SuppressWarnings("unchecked")
        private String scriptSource() throws Exception {
            givenWindowCount(1L);
            filter(1).doFilter(request("/portal/api/signup"), new MockHttpServletResponse(), chain);

            ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
            verify(redisTemplate).execute(script.capture(), anyList(), anyString());
            return script.getValue().getScriptAsString();
        }
    }
}
