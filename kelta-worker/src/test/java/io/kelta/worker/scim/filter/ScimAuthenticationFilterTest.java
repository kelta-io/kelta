package io.kelta.worker.scim.filter;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ScimAuthenticationFilter")
class ScimAuthenticationFilterTest {

    private JdbcTemplate jdbcTemplate;
    private ScimAuthenticationFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        filter = new ScimAuthenticationFilter(jdbcTemplate, objectMapper);
    }

    @Test
    @DisplayName("non-SCIM paths are not filtered")
    void nonScimPathsSkipped() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("SCIM paths are filtered")
    void scimPathsFiltered() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/scim/v2/Users");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    @DisplayName("missing Authorization header returns 401")
    void missingAuthHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/scim/v2/Users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/scim+json");
    }

    @Test
    @DisplayName("invalid token returns 401")
    void invalidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/scim/v2/Users");
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(jdbcTemplate.queryForList(contains("scim_client"), anyString()))
                .thenReturn(List.of());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("valid token sets tenant ID and continues chain")
    void validToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/scim/v2/Users");
        request.addHeader("Authorization", "Bearer valid-token-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        String tokenHash = ScimAuthenticationFilter.hashToken("valid-token-123");
        when(jdbcTemplate.queryForList(contains("scim_client"), eq(tokenHash)))
                .thenReturn(List.of(Map.of("tenant_id", "t1", "name", "Okta")));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        // Verify the chain was invoked (meaning auth passed)
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("deactivated client returns 401")
    void deactivatedClient() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/scim/v2/Users");
        request.addHeader("Authorization", "Bearer some-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // With the atomic UPDATE...RETURNING, an inactive client returns no rows
        // (filtered by AND active = true in the WHERE clause)
        String tokenHash = ScimAuthenticationFilter.hashToken("some-token");
        when(jdbcTemplate.queryForList(contains("scim_client"), eq(tokenHash)))
                .thenReturn(List.of());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("every header accessor sees the token's tenant, not one the caller sent")
    void tenantHeaderThroughEveryAccessor() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/scim/v2/Users");
        request.addHeader("Authorization", "Bearer valid-token-123");
        request.addHeader("X-Tenant-ID", "caller-chosen");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        String tokenHash = ScimAuthenticationFilter.hashToken("valid-token-123");
        when(jdbcTemplate.queryForList(contains("scim_client"), eq(tokenHash)))
                .thenReturn(List.of(Map.of("tenant_id", "t1", "name", "Okta")));

        filter.doFilter(request, response, chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertThat(forwarded.getHeader("X-Tenant-ID")).isEqualTo("t1");
        // Spring's @RequestHeader reads getHeaders.
        assertThat(Collections.list(forwarded.getHeaders("x-tenant-id"))).containsExactly("t1");
        List<String> names = Collections.list(forwarded.getHeaderNames());
        assertThat(names.stream().filter("X-Tenant-ID"::equalsIgnoreCase)).hasSize(1);
        assertThat(names).contains("Authorization");
        assertThat(forwarded.getHeader("Authorization")).isEqualTo("Bearer valid-token-123");
    }

    @Test
    @DisplayName("hashToken produces consistent SHA-256 hex")
    void hashTokenConsistency() {
        String hash1 = ScimAuthenticationFilter.hashToken("test-token");
        String hash2 = ScimAuthenticationFilter.hashToken("test-token");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex length
    }
}
