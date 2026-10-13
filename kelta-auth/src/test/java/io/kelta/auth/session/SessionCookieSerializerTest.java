package io.kelta.auth.session;

import io.kelta.auth.config.CookieDomainResolver;
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
    @DisplayName("on a public host the cookie is Secure, HttpOnly, SameSite=None, host-only, browser-session")
    void publicHost() {
        String header = write(new SessionCookieSerializer(NAME, "None", new CookieDomainResolver()), "auth.kelta.io");

        assertThat(header).startsWith(NAME + "=")
                .contains("; Path=/").contains("; Secure").contains("; HttpOnly").contains("; SameSite=None")
                .doesNotContain("Domain=").doesNotContain("Max-Age").doesNotContain("Expires");
    }

    @Test
    @DisplayName("a customer custom domain gets the same cookie, scoped to that host")
    void customDomain() {
        String header = write(new SessionCookieSerializer(NAME, "None", new CookieDomainResolver()), "login.example.org");

        assertThat(header).contains("; Secure").contains("; SameSite=None").doesNotContain("Domain=");
    }

    @Test
    @DisplayName("on a loopback host (plain-http development) there is no Secure and so no SameSite=None")
    void loopbackHost() {
        String header = write(new SessionCookieSerializer(NAME, "None", new CookieDomainResolver()), "auth.localhost");

        assertThat(header).contains("; HttpOnly").doesNotContain("Secure").doesNotContain("SameSite");
    }

    @Test
    @DisplayName("Lax and Strict apply on loopback hosts too")
    void laxOnLoopback() {
        String header = write(new SessionCookieSerializer(NAME, "lax", new CookieDomainResolver()), "localhost");

        assertThat(header).contains("; SameSite=Lax").doesNotContain("Secure");
    }

    @Test
    @DisplayName("a blank setting leaves SameSite off")
    void blankSameSite() {
        String header = write(new SessionCookieSerializer(NAME, " ", new CookieDomainResolver()), "auth.kelta.io");

        assertThat(header).contains("; Secure").doesNotContain("SameSite");
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

    private static String write(SessionCookieSerializer serializer, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setServerName(host);
        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieValue(request, response, "session-1"));
        return response.getHeader("Set-Cookie");
    }
}
