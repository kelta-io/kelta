package io.kelta.auth.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kelta.auth.model.KeltaUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Credentials are erased from the authenticated principal: after authentication the password
 * hash is neither held in the HTTP session nor written to {@code oauth2_authorization.attributes},
 * and authorization rows written before erasure still deserialize.
 */
@DisplayName("Authenticated principal — credentials erased")
class AuthenticatedPrincipalCredentialsTest {

    private static final String HASH = "$2a$10$exampleexampleexampleexampleexampleexampleexample";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private static KeltaUserDetails user(String passwordHash) {
        return new KeltaUserDetails("11111111-0000-0000-0000-000000000001", "user@example.test",
                "aaaaaaaa-0000-0000-0000-000000000001", "profile-1", "Standard", "Test User",
                passwordHash, true, false, false);
    }

    /** Serializes an authorization's attributes the way the JDBC parameters mapper does. */
    private static String writeAttributes(ObjectMapper mapper, KeltaUserDetails principal) throws Exception {
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_USER"));
        // OAuth2Authorization holds its attributes as an unmodifiable view of a HashMap.
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(Principal.class.getName(), auth);
        return mapper.writeValueAsString(Collections.unmodifiableMap(attributes));
    }

    /** Reads attributes back the way the JDBC row mapper does; returns the stored principal. */
    private static KeltaUserDetails readPrincipal(ObjectMapper mapper, String attributes) throws Exception {
        Map<String, Object> read = mapper.readValue(attributes, MAP);
        Authentication auth = (Authentication) read.get(Principal.class.getName());
        return (KeltaUserDetails) auth.getPrincipal();
    }

    /**
     * Attributes as stored before credentials were erased: the principal carries
     * {@code passwordHash} and no {@code localCredential}.
     */
    private static String legacyAttributes(ObjectMapper mapper, String passwordHash) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(writeAttributes(mapper, user(passwordHash)));
        ObjectNode principal = (ObjectNode) root.get(Principal.class.getName()).get("principal");
        principal.remove("localCredential");
        principal.put("passwordHash", passwordHash);
        return mapper.writeValueAsString(root);
    }

    @Nested
    @DisplayName("authorization store serialization")
    class Serialization {

        private final ObjectMapper mapper = AuthorizationServerConfig.authorizationObjectMapper();

        @Test
        @DisplayName("never writes the password hash, even for a principal that still holds one")
        void neverWritesPasswordHash() throws Exception {
            String attributes = writeAttributes(mapper, user(HASH));

            assertThat(attributes).doesNotContain(HASH).doesNotContain("passwordHash");
        }

        @Test
        @DisplayName("round-trips the principal without its hash and keeps equality and the local-credential flag")
        void roundTrips() throws Exception {
            KeltaUserDetails original = user(HASH);

            KeltaUserDetails restored = readPrincipal(mapper, writeAttributes(mapper, original));

            assertThat(restored).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(restored.getPassword()).isNull();
            assertThat(restored.hasLocalCredential()).isTrue();
            assertThat(restored.getEmail()).isEqualTo(original.getEmail());
            assertThat(restored.getUserType()).isEqualTo("INTERNAL");
        }

        @Test
        @DisplayName("reads a stored row that still carries the password hash, and drops it")
        void readsLegacyRowWithPasswordHash() throws Exception {
            String legacy = legacyAttributes(mapper, HASH);
            assertThat(legacy).contains(HASH);

            KeltaUserDetails restored = readPrincipal(mapper, legacy);

            assertThat(restored).isEqualTo(user(HASH));
            assertThat(restored.getPassword()).isNull();
            assertThat(restored.hasLocalCredential()).isTrue();
        }

        @Test
        @DisplayName("a stored row with an empty password hash reads as no local credential")
        void readsLegacyRowWithoutLocalPassword() throws Exception {
            KeltaUserDetails restored = readPrincipal(mapper, legacyAttributes(mapper, ""));

            assertThat(restored.getPassword()).isNull();
            assertThat(restored.hasLocalCredential()).isFalse();
        }
    }

    @Nested
    @SpringJUnitWebConfig(OidcSignOutFlowTest.Config.class)
    @DisplayName("form login through the real filter chains and JDBC store")
    class FormLogin {

        private static final String CALLBACK = "http://app.example.test:8080/tenant-a/auth/callback";

        @Autowired
        private WebApplicationContext context;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private AuthenticationConfiguration authenticationConfiguration;

        private MockMvc mvc;

        @BeforeEach
        void setUp() {
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        }

        @Test
        @DisplayName("the shared AuthenticationManager erases the principal's password hash")
        void authenticationManagerErasesCredentials() throws Exception {
            Authentication result = authenticationConfiguration.getAuthenticationManager().authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            "user@example.test", "correct-horse-battery-staple"));

            KeltaUserDetails principal = (KeltaUserDetails) result.getPrincipal();
            assertThat(principal.getPassword()).isNull();
            assertThat(principal.hasLocalCredential()).isTrue();
            assertThat(result.getCredentials()).isNull();
        }

        @Test
        @DisplayName("neither the session nor the stored authorization holds the hash, and the code still redeems")
        void formLoginStoresNoHash() throws Exception {
            MockHttpSession session = new MockHttpSession();
            String verifier = UUID.randomUUID() + "-" + UUID.randomUUID();
            String authorize = UriComponentsBuilder.fromPath("/oauth2/authorize")
                    .queryParam("response_type", "code")
                    .queryParam("client_id", "kelta-platform")
                    .queryParam("redirect_uri", CALLBACK)
                    .queryParam("scope", "openid profile email")
                    .queryParam("state", "state-1")
                    .queryParam("code_challenge", sha256(verifier))
                    .queryParam("code_challenge_method", "S256")
                    .encode().build().toUriString();

            mvc.perform(get(URI.create(authorize)).accept(MediaType.TEXT_HTML).session(session));
            MockHttpServletResponse loggedIn = mvc.perform(post("/login")
                            .param("username", "user@example.test")
                            .param("password", "correct-horse-battery-staple")
                            .with(csrf())
                            .session(session))
                    .andReturn().getResponse();
            assertThat(loggedIn.getRedirectedUrl()).contains("/oauth2/authorize");
            MockHttpServletResponse toCallback = mvc.perform(get(URI.create(loggedIn.getRedirectedUrl()))
                            .accept(MediaType.TEXT_HTML).session(session))
                    .andReturn().getResponse();
            assertThat(toCallback.getRedirectedUrl()).startsWith(CALLBACK);

            SecurityContext securityContext = (SecurityContext) session.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            KeltaUserDetails sessionPrincipal =
                    (KeltaUserDetails) securityContext.getAuthentication().getPrincipal();
            assertThat(sessionPrincipal.getPassword()).isNull();

            String hash = OidcSignOutFlowTest.ServerBeans.PasswordHolder.HASH;
            List<String> stored = jdbcTemplate.query("SELECT attributes FROM oauth2_authorization",
                    (rs, row) -> new String(rs.getBytes("attributes"), StandardCharsets.UTF_8));
            assertThat(stored).isNotEmpty().allSatisfy(attributes -> assertThat(attributes)
                    .contains("io.kelta.auth.model.KeltaUserDetails")
                    .doesNotContain(hash)
                    .doesNotContain("passwordHash"));

            String code = UriComponentsBuilder.fromUriString(toCallback.getRedirectedUrl())
                    .build().getQueryParams().getFirst("code");
            MockHttpServletResponse token = mvc.perform(post("/oauth2/token")
                            .param("grant_type", "authorization_code")
                            .param("code", code)
                            .param("redirect_uri", CALLBACK)
                            .param("client_id", "kelta-platform")
                            .param("code_verifier", verifier))
                    .andReturn().getResponse();
            assertThat(token.getStatus()).as("%s", token.getContentAsString()).isEqualTo(200);
        }

        private static String sha256(String value) throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        }
    }
}
