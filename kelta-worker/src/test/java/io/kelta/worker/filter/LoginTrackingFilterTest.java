package io.kelta.worker.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link LoginTrackingFilter}.
 */
class LoginTrackingFilterTest {

    private JdbcTemplate jdbcTemplate;
    private Map<String, Long> throttleCache;
    private LoginTrackingFilter filter;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        throttleCache = new ConcurrentHashMap<>();
        filter = new LoginTrackingFilter(jdbcTemplate, throttleCache);
    }

    @Test
    void shouldTrackLoginWhenValidHeadersAndUserFound() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "alice@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");
        request.setRemoteAddr("192.168.1.1");
        request.addHeader("User-Agent", "Mozilla/5.0");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jdbcTemplate.queryForObject(
                eq("SELECT id FROM platform_user WHERE tenant_id = ? AND email = ? LIMIT 1"),
                eq(String.class), eq("tenant-123"), eq("alice@example.com")))
                .thenReturn("user-uuid-1");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);

        verify(jdbcTemplate).update(
                contains("UPDATE platform_user SET last_login_at"),
                any(), any(), eq("user-uuid-1"));

        verify(jdbcTemplate).update(
                contains("INSERT INTO login_history"),
                any(String.class), eq("user-uuid-1"), eq("tenant-123"),
                any(), eq("192.168.1.1"), eq("OAUTH"), eq("SUCCESS"),
                eq("Mozilla/5.0"), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any());

        verify(jdbcTemplate).update(
                contains("INSERT INTO security_audit_log"),
                any(String.class), eq("tenant-123"), eq("LOGIN_SUCCESS"), eq("AUTH"),
                eq("user-uuid-1"), eq("alice@example.com"),
                eq("USER"), eq("user-uuid-1"), eq("alice@example.com"),
                any(String.class), eq("192.168.1.1"), eq("Mozilla/5.0"), any());

        assertTrue(throttleCache.containsKey("tenant-123:alice@example.com"));
    }

    @Test
    void shouldRecordGeoColumnsFromGatewayHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "alice@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");
        request.setRemoteAddr("203.0.113.50");
        request.addHeader("User-Agent", "Mozilla/5.0");
        request.addHeader("X-Geo-Country", "PT");
        request.addHeader("X-Geo-Region", "Lisbon");
        request.addHeader("X-Geo-City", "M%C3%BCnchen"); // percent-encoded on the wire
        request.addHeader("X-Geo-Lat", "38.6979");
        request.addHeader("X-Geo-Lon", "-9.4207");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jdbcTemplate.queryForObject(
                eq("SELECT id FROM platform_user WHERE tenant_id = ? AND email = ? LIMIT 1"),
                eq(String.class), eq("tenant-123"), eq("alice@example.com")))
                .thenReturn("user-uuid-1");

        filter.doFilterInternal(request, response, chain);

        verify(jdbcTemplate).update(
                contains("INSERT INTO login_history"),
                any(String.class), eq("user-uuid-1"), eq("tenant-123"),
                any(), eq("203.0.113.50"), eq("OAUTH"), eq("SUCCESS"),
                eq("Mozilla/5.0"), eq("PT"), eq("Lisbon"), eq("München"),
                eq(38.6979), eq(-9.4207), any(), any());
    }

    @Test
    void shouldIgnoreMalformedGeoHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "alice@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");
        request.setRemoteAddr("203.0.113.50");
        request.addHeader("X-Geo-Country", "NOT-A-COUNTRY");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jdbcTemplate.queryForObject(
                eq("SELECT id FROM platform_user WHERE tenant_id = ? AND email = ? LIMIT 1"),
                eq(String.class), eq("tenant-123"), eq("alice@example.com")))
                .thenReturn("user-uuid-1");

        filter.doFilterInternal(request, response, chain);

        // Malformed country → the whole stamp is dropped, never a failed request
        verify(chain).doFilter(request, response);
        verify(jdbcTemplate).update(
                contains("INSERT INTO login_history"),
                any(String.class), eq("user-uuid-1"), eq("tenant-123"),
                any(), eq("203.0.113.50"), eq("OAUTH"), eq("SUCCESS"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any());
    }

    @Test
    void shouldSkipTrackingWhenUserIdHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-Tenant-ID", "tenant-123");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void shouldSkipTrackingWhenTenantIdHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "alice@example.com");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void shouldAutoProvisionUserWhenNotFoundInDatabase() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "new-user@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");
        request.addHeader("X-Forwarded-User", "new-user");
        request.setRemoteAddr("192.168.1.1");
        request.addHeader("User-Agent", "Mozilla/5.0");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jdbcTemplate.queryForObject(
                eq("SELECT id FROM platform_user WHERE tenant_id = ? AND email = ? LIMIT 1"),
                eq(String.class), eq("tenant-123"), eq("new-user@example.com")))
                .thenReturn(null)
                .thenReturn("provisioned-uuid");

        when(jdbcTemplate.queryForObject(
                contains("SELECT id FROM profile"),
                eq(String.class), eq("tenant-123")))
                .thenReturn("standard-user-profile-id");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);

        verify(jdbcTemplate).update(
                contains("INSERT INTO platform_user"),
                any(String.class), eq("tenant-123"), eq("new-user@example.com"),
                eq("new-user"), eq("standard-user-profile-id"));

        verify(jdbcTemplate).update(
                contains("UPDATE platform_user SET last_login_at"),
                any(), any(), eq("provisioned-uuid"));

        verify(jdbcTemplate).update(
                contains("INSERT INTO login_history"),
                any(String.class), eq("provisioned-uuid"), eq("tenant-123"),
                any(), eq("192.168.1.1"), eq("OAUTH"), eq("SUCCESS"),
                eq("Mozilla/5.0"), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any());
    }

    @Test
    void shouldSkipTrackingWhenProvisioningFails() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "unknown@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any()))
                .thenReturn(null);
        when(jdbcTemplate.update(contains("INSERT INTO platform_user"), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("FK constraint violation"));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(jdbcTemplate, never()).update(contains("UPDATE platform_user SET last_login_at"), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO login_history"),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldThrottleWithin30MinuteInterval() throws ServletException, IOException {
        long recentTime = java.time.Instant.now().getEpochSecond() - 60;
        throttleCache.put("tenant-123:alice@example.com", recentTime);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "alice@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldAllowTrackingAfterIntervalExpires() throws ServletException, IOException {
        long oldTime = java.time.Instant.now().getEpochSecond()
                - LoginTrackingFilter.TRACKING_INTERVAL_SECONDS - 60;
        throttleCache.put("tenant-123:alice@example.com", oldTime);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "alice@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");
        request.setRemoteAddr("10.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any()))
                .thenReturn("user-uuid-1");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(jdbcTemplate).update(contains("UPDATE platform_user"), any(), any(), any());
        verify(jdbcTemplate).update(contains("INSERT INTO login_history"),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldNeverBlockFilterChainOnException() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "alice@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any()))
                .thenThrow(new RuntimeException("Database connection failed"));

        assertDoesNotThrow(() -> filter.doFilterInternal(request, response, chain));
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldExtractClientIpFromXForwardedForHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18, 150.172.238.178");
        request.setRemoteAddr("127.0.0.1");

        String ip = LoginTrackingFilter.extractClientIp(request);
        assertEquals("203.0.113.50", ip);
    }

    @Test
    void shouldFallBackToRemoteAddrWhenNoForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");

        String ip = LoginTrackingFilter.extractClientIp(request);
        assertEquals("192.168.1.100", ip);
    }

    @Test
    void shouldPreferGatewayResolvedClientIpOverForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Kelta-Client-Ip", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.7, 10.42.0.5");
        request.setRemoteAddr("10.42.1.9");

        assertEquals("203.0.113.7", LoginTrackingFilter.extractClientIp(request));
    }

    @Test
    void shouldFallBackToForwardedForWhenGatewayHeaderBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Kelta-Client-Ip", "  ");
        request.addHeader("X-Forwarded-For", "203.0.113.50");
        request.setRemoteAddr("127.0.0.1");

        assertEquals("203.0.113.50", LoginTrackingFilter.extractClientIp(request));
    }

    @Test
    void shouldRecordGatewayResolvedIpNotSpoofedLeftmostHop() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "alice@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");
        request.addHeader("X-Kelta-Client-Ip", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.7");
        request.setRemoteAddr("10.42.1.9");
        request.addHeader("User-Agent", "Mozilla/5.0");

        when(jdbcTemplate.queryForObject(
                eq("SELECT id FROM platform_user WHERE tenant_id = ? AND email = ? LIMIT 1"),
                eq(String.class), eq("tenant-123"), eq("alice@example.com")))
                .thenReturn("user-uuid-1");

        filter.doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(jdbcTemplate).update(
                contains("INSERT INTO login_history"),
                any(String.class), eq("user-uuid-1"), eq("tenant-123"),
                any(), eq("203.0.113.7"), eq("OAUTH"), eq("SUCCESS"),
                eq("Mozilla/5.0"), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any());
        verify(jdbcTemplate).update(
                contains("INSERT INTO security_audit_log"),
                any(String.class), eq("tenant-123"), eq("LOGIN_SUCCESS"), eq("AUTH"),
                eq("user-uuid-1"), eq("alice@example.com"),
                eq("USER"), eq("user-uuid-1"), eq("alice@example.com"),
                any(String.class), eq("203.0.113.7"), eq("Mozilla/5.0"), any());
    }

    @Test
    void shouldTruncateUserAgentExceeding500Chars() {
        String longAgent = "A".repeat(600);
        String truncated = LoginTrackingFilter.truncateUserAgent(longAgent);
        assertNotNull(truncated);
        assertEquals(500, truncated.length());
    }

    @Test
    void shouldReturnNullForNullUserAgent() {
        assertNull(LoginTrackingFilter.truncateUserAgent(null));
    }

    @Test
    void shouldNotTruncateShortUserAgent() {
        String agent = "Mozilla/5.0";
        assertEquals("Mozilla/5.0", LoginTrackingFilter.truncateUserAgent(agent));
    }

    @Test
    void shouldSkipTrackingWhenUserIdLooksLikeUuid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        request.addHeader("X-Tenant-ID", "tenant-123");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldSyncProfileFromGroupsForExistingUser() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("X-User-Id", "alice@example.com");
        request.addHeader("X-Tenant-ID", "tenant-123");
        request.addHeader("X-Forwarded-Groups", "admins");
        request.setRemoteAddr("192.168.1.1");
        request.addHeader("User-Agent", "Mozilla/5.0");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jdbcTemplate.queryForObject(
                eq("SELECT id FROM platform_user WHERE tenant_id = ? AND email = ? LIMIT 1"),
                eq(String.class), eq("tenant-123"), eq("alice@example.com")))
                .thenReturn("user-uuid-1");

        when(jdbcTemplate.queryForObject(
                contains("SELECT groups_profile_mapping FROM oidc_provider"),
                eq(String.class), eq("tenant-123")))
                .thenReturn("{\"admins\": \"System Administrator\"}");

        when(jdbcTemplate.queryForObject(
                contains("SELECT id FROM profile WHERE tenant_id = ? AND name = ?"),
                eq(String.class), eq("tenant-123"), eq("System Administrator")))
                .thenReturn("sysadmin-profile-id");

        when(jdbcTemplate.queryForObject(
                eq("SELECT profile_id FROM platform_user WHERE id = ?"),
                eq(String.class), eq("user-uuid-1")))
                .thenReturn("standard-user-profile-id");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);

        verify(jdbcTemplate).update(
                eq("UPDATE platform_user SET profile_id = ?, updated_at = NOW() WHERE id = ?"),
                eq("sysadmin-profile-id"), eq("user-uuid-1"));
    }

    @Test
    void shouldNotSyncProfileWhenAlreadyCorrect() {
        when(jdbcTemplate.queryForObject(
                contains("SELECT groups_profile_mapping FROM oidc_provider"),
                eq(String.class), eq("tenant-123")))
                .thenReturn("{\"admins\": \"System Administrator\"}");

        when(jdbcTemplate.queryForObject(
                contains("SELECT id FROM profile WHERE tenant_id = ? AND name = ?"),
                eq(String.class), eq("tenant-123"), eq("System Administrator")))
                .thenReturn("sysadmin-profile-id");

        when(jdbcTemplate.queryForObject(
                eq("SELECT profile_id FROM platform_user WHERE id = ?"),
                eq(String.class), eq("user-uuid-1")))
                .thenReturn("sysadmin-profile-id");

        filter.syncProfileFromGroups("user-uuid-1", "tenant-123", "admins");

        verify(jdbcTemplate, never()).update(
                contains("UPDATE platform_user SET profile_id"),
                any(), any());
    }

    @Test
    void shouldNotSyncProfileWhenNoGroupsHeader() {
        filter.syncProfileFromGroups("user-uuid-1", "tenant-123", null);

        verify(jdbcTemplate, never()).queryForObject(
                contains("groups_profile_mapping"),
                eq(String.class), any());
    }

    @Test
    void shouldTrackDifferentUsersIndependently() throws ServletException, IOException {
        FilterChain chain = mock(FilterChain.class);

        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("tenant-123"), eq("alice@example.com")))
                .thenReturn("user-1");
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("tenant-123"), eq("bob@example.com")))
                .thenReturn("user-2");

        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/accounts");
        req1.addHeader("X-User-Id", "alice@example.com");
        req1.addHeader("X-Tenant-ID", "tenant-123");
        filter.doFilterInternal(req1, new MockHttpServletResponse(), chain);

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/accounts");
        req2.addHeader("X-User-Id", "bob@example.com");
        req2.addHeader("X-Tenant-ID", "tenant-123");
        filter.doFilterInternal(req2, new MockHttpServletResponse(), chain);

        assertTrue(throttleCache.containsKey("tenant-123:alice@example.com"));
        assertTrue(throttleCache.containsKey("tenant-123:bob@example.com"));

        verify(jdbcTemplate, times(2)).update(contains("UPDATE platform_user"), any(), any(), any());
    }

    @Test
    void shouldPreferSystemAdministratorWhenMultipleGroupsMatch() {
        when(jdbcTemplate.queryForObject(
                contains("SELECT groups_profile_mapping FROM oidc_provider"),
                eq(String.class), eq("tenant-123")))
                .thenReturn("{\"developers\": \"Solution Manager\", \"admins\": \"System Administrator\"}");

        when(jdbcTemplate.queryForObject(
                contains("SELECT id FROM profile WHERE tenant_id = ? AND name = ?"),
                eq(String.class), eq("tenant-123"), eq("Solution Manager")))
                .thenReturn("solution-manager-profile-id");

        when(jdbcTemplate.queryForObject(
                contains("SELECT id FROM profile WHERE tenant_id = ? AND name = ?"),
                eq(String.class), eq("tenant-123"), eq("System Administrator")))
                .thenReturn("sysadmin-profile-id");

        when(jdbcTemplate.queryForObject(
                eq("SELECT profile_id FROM platform_user WHERE id = ?"),
                eq(String.class), eq("user-uuid-1")))
                .thenReturn("solution-manager-profile-id");

        filter.syncProfileFromGroups("user-uuid-1", "tenant-123", "developers,admins");

        verify(jdbcTemplate).update(
                eq("UPDATE platform_user SET profile_id = ?, updated_at = NOW() WHERE id = ?"),
                eq("sysadmin-profile-id"), eq("user-uuid-1"));
    }
}
