package io.kelta.auth.session;

import io.kelta.auth.config.CookieDomainResolver;
import io.kelta.auth.config.CookieDomainResolver.SecureMode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer.CookieValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SessionCookieSerializer — the HTTP session cookie")
class SessionCookieSerializerTest {

    private static final String NAME = "kelta_auth_session";

    @Test
    @DisplayName("behind a TLS-terminating proxy the cookie is Secure, HttpOnly, SameSite=None, host-only, browser-session")
    void behindTlsProxy() {
        String header = write(serializer("None"), "auth.kelta.io", "http", "https");

        assertThat(header).startsWith(NAME + "=")
                .contains("; Path=/").contains("; Secure").contains("; HttpOnly").contains("; SameSite=None")
                .doesNotContain("Domain=").doesNotContain("Max-Age").doesNotContain("Expires");
    }

    @Test
    @DisplayName("a customer custom domain gets the same cookie, scoped to that host")
    void customDomain() {
        String header = write(serializer("None"), "login.example.org", "http", "https");

        assertThat(header).contains("; Secure").contains("; SameSite=None").doesNotContain("Domain=");
    }

    @Test
    @DisplayName("TLS straight to the service is Secure too")
    void directTls() {
        assertThat(write(serializer("None"), "auth.example.org", "https", null))
                .contains("; Secure").contains("; SameSite=None");
    }

    @Test
    @DisplayName("a plain-http install on a LAN host name gets no Secure, and SameSite=Lax in place of None")
    void plainHttpLanHost() {
        assertThat(write(serializer("None"), "kelta.lan", "http", null))
                .contains("; HttpOnly").contains("; SameSite=Lax").doesNotContain("Secure");
        assertThat(write(serializer("None"), "kelta.lan", "http", "http"))
                .contains("; SameSite=Lax").doesNotContain("Secure");
    }

    @Test
    @DisplayName("local development over http on a *.localhost host: no Secure, SameSite=Lax")
    void loopbackHost() {
        assertThat(write(serializer("None"), "auth.localhost", "http", null))
                .contains("; HttpOnly").contains("; SameSite=Lax").doesNotContain("Secure");
    }

    @Test
    @DisplayName("a configured Lax or Strict is kept without Secure")
    void laxWithoutSecure() {
        assertThat(write(serializer("lax"), "localhost", "http", null))
                .contains("; SameSite=Lax").doesNotContain("Secure");
        assertThat(write(serializer("strict"), "kelta.lan", "http", null))
                .contains("; SameSite=Strict").doesNotContain("Secure");
    }

    @Test
    @DisplayName("a blank setting leaves SameSite off")
    void blankSameSite() {
        String header = write(serializer(" "), "auth.kelta.io", "http", "https");

        assertThat(header).contains("; Secure").doesNotContain("SameSite");
    }

    @Test
    @DisplayName("cookie-secure=always and never override the scheme")
    void overrides() {
        assertThat(write(new SessionCookieSerializer(NAME, "None", new CookieDomainResolver(SecureMode.ALWAYS)),
                "kelta.lan", "http", null)).contains("; Secure").contains("; SameSite=None");
        assertThat(write(new SessionCookieSerializer(NAME, "None", new CookieDomainResolver(SecureMode.NEVER)),
                "auth.kelta.io", "http", "https")).doesNotContain("Secure").contains("; SameSite=Lax");
    }

    @Test
    @DisplayName("an unknown SameSite value fails at startup")
    void unknownSameSite() {
        assertThatThrownBy(() -> new SessionCookieSerializer(NAME, "sometimes", new CookieDomainResolver()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cookie-same-site");
    }

    @Test
    @DisplayName("the session id is read back from the cookie it wrote")
    void readsSessionId() {
        SessionCookieSerializer serializer = new SessionCookieSerializer(NAME, "None", new CookieDomainResolver());
        MockHttpServletRequest request = new MockHttpServletRequest();
        String encoded = Base64.getEncoder().encodeToString("session-1".getBytes(StandardCharsets.UTF_8));
        request.setCookies(new Cookie(NAME, encoded), new Cookie("JSESSIONID", "container-session"));

        assertThat(serializer.readCookieValues(request)).containsExactly("session-1");
    }

    private static SessionCookieSerializer serializer(String sameSite) {
        return new SessionCookieSerializer(NAME, sameSite, new CookieDomainResolver());
    }

    private static String write(SessionCookieSerializer serializer, String host, String scheme, String forwardedProto) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setServerName(host);
        request.setScheme(scheme);
        request.setSecure("https".equals(scheme));
        if (forwardedProto != null) {
            request.addHeader("X-Forwarded-Proto", forwardedProto);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieValue(request, response, "session-1"));
        return response.getHeader("Set-Cookie");
    }
}
