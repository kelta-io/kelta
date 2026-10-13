package io.kelta.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.kelta.auth.controller.InternalSessionRevocationController;
import io.kelta.auth.session.InMemoryIndexedSessionRepository;
import io.kelta.auth.session.UserSessionRevoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.net.URI;

import static io.kelta.auth.config.PasswordChangeSignOutFlowTest.PasswordBeans.TENANT_ID;
import static io.kelta.auth.config.PasswordChangeSignOutFlowTest.PasswordBeans.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * kelta-worker's call after an administrator reset, deactivated or removed a user, through the
 * real filter chains: no CSRF token, no session, no sign-in — only the internal token admits it.
 * The user's every session (none kept) and refresh token end; the same email address in another
 * tenant is untouched.
 */
@SpringJUnitWebConfig(InternalSessionRevocationFlowTest.Config.class)
@DisplayName("Internal session revocation — kelta-worker signs a user out everywhere")
class InternalSessionRevocationFlowTest {

    static final String INTERNAL_TOKEN = "flow-test-internal-token";
    static final String PATH = "/internal/tenants/" + TENANT_ID + "/users/" + USER_ID + "/revoke-sessions";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private InMemoryIndexedSessionRepository sessions;

    @Autowired
    private OAuth2AuthorizationService authorizations;

    @Autowired
    private RegisteredClientRepository clients;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = OidcSignOutFlowTest.Config.mockMvc(context);
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
    }

    @Test
    @DisplayName("refused without the internal token or with a wrong one; nothing ends")
    void refusedWithoutTheToken() throws Exception {
        OidcSignOutFlowTest.Browser laptop = new OidcSignOutFlowTest.Browser();
        String refresh = PasswordChangeSignOutFlowTest.signIn(mvc, laptop, true).get("refresh_token").asString();

        assertThat(call(post(URI.create(PATH))).getStatus()).isEqualTo(403);
        assertThat(call(post(URI.create(PATH)).header("X-Internal-Token", "wrong")).getStatus()).isEqualTo(403);

        assertThat(sessions.findById(laptop.sessionId())).isNotNull();
        assertThat(PasswordChangeSignOutFlowTest.refresh(mvc, refresh).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("with the token: every session of the user ends and earlier refresh tokens are rejected")
    void revokesWithTheToken() throws Exception {
        OidcSignOutFlowTest.Browser laptop = new OidcSignOutFlowTest.Browser();
        String laptopRefresh = PasswordChangeSignOutFlowTest.signIn(mvc, laptop, true).get("refresh_token").asString();
        OidcSignOutFlowTest.Browser phone = new OidcSignOutFlowTest.Browser();
        String phoneRefresh = PasswordChangeSignOutFlowTest.signIn(mvc, phone, true).get("refresh_token").asString();
        String otherTenantSession = PasswordChangeSignOutFlowTest.PasswordBeans.otherTenantSession(sessions);
        String otherTenantAuthorization =
                PasswordChangeSignOutFlowTest.PasswordBeans.otherTenantAuthorization(authorizations, clients);

        MockHttpServletResponse response = call(post(URI.create(PATH)).header("X-Internal-Token", INTERNAL_TOKEN));

        assertThat(response.getStatus()).as("%s", response.getContentAsString()).isEqualTo(200);
        assertThat(sessions.findById(laptop.sessionId())).isNull();
        assertThat(sessions.findById(phone.sessionId())).isNull();
        assertThat(PasswordChangeSignOutFlowTest.refresh(mvc, laptopRefresh).getContentAsString()).contains("invalid_grant");
        assertThat(PasswordChangeSignOutFlowTest.refresh(mvc, phoneRefresh).getContentAsString()).contains("invalid_grant");
        assertThat(sessions.findById(otherTenantSession)).as("same email, other tenant").isNotNull();
        assertThat(authorizations.findById(otherTenantAuthorization)).as("other tenant's authorization").isNotNull();
    }

    @Configuration
    @Import({OidcSignOutFlowTest.ServerBeans.class, OidcSignOutFlowTest.InMemorySessions.class,
            PasswordChangeSignOutFlowTest.PasswordBeans.class})
    static class Config {

        @Bean
        DataSource dataSource() {
            return PasswordChangeSignOutFlowTest.PasswordBeans.database();
        }

        @Bean
        AuthProperties authProperties() {
            return new AuthProperties();
        }

        @Bean
        JWKSource<SecurityContext> jwkSource() throws Exception {
            return new ImmutableJWKSet<>(new JWKSet(new RSAKeyGenerator(2048).keyID("test").generate()));
        }

        @Bean
        InternalSessionRevocationController internalSessionRevocationController(UserSessionRevoker revoker) {
            return new InternalSessionRevocationController(revoker, INTERNAL_TOKEN);
        }
    }
}
