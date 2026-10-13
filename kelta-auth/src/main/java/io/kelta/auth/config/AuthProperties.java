package io.kelta.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "kelta.auth")
public class AuthProperties {

    private String issuerUri;
    private String workerUrl;
    private String cookieDomain = "localhost";
    private String jwkSet;
    private String uiBaseUrl;
    private String corsAllowedOrigins;
    private String supersetClientId;
    private String supersetClientSecret;
    private String supersetRedirectUri;

    /**
     * Internal service OAuth2 clients registered for the {@code client_credentials}
     * grant. Keyed by client_id (e.g. {@code gateway-internal},
     * {@code auth-internal}, {@code ai-internal}); each entry supplies the client
     * secret used by that caller. Clients whose secret is blank are skipped at
     * startup so the registrar stays opt-in per environment.
     */
    private Map<String, InternalClient> internalClients = new LinkedHashMap<>();

    private DirectLogin directLogin = new DirectLogin();

    private Saml saml = new Saml();

    private Session session = new Session();

    private AuthorizationRetention authorizationRetention = new AuthorizationRetention();

    /**
     * Initial password for the Flyway baseline's platform admin ({@code admin@kelta.local}),
     * applied once by {@link BaselineAdminPasswordInitializer} while the account still carries
     * the baseline hash. Blank generates one and prints it once in the auth log.
     */
    private String bootstrapAdminPassword;

    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }

    public String getWorkerUrl() { return workerUrl; }
    public void setWorkerUrl(String workerUrl) { this.workerUrl = workerUrl; }

    public String getCookieDomain() { return cookieDomain; }
    public void setCookieDomain(String cookieDomain) { this.cookieDomain = cookieDomain; }

    public String getJwkSet() { return jwkSet; }
    public void setJwkSet(String jwkSet) { this.jwkSet = jwkSet; }

    public String getUiBaseUrl() { return uiBaseUrl; }
    public void setUiBaseUrl(String uiBaseUrl) { this.uiBaseUrl = uiBaseUrl; }

    public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }

    public String getSupersetClientId() { return supersetClientId; }
    public void setSupersetClientId(String supersetClientId) { this.supersetClientId = supersetClientId; }

    public String getSupersetClientSecret() { return supersetClientSecret; }
    public void setSupersetClientSecret(String supersetClientSecret) { this.supersetClientSecret = supersetClientSecret; }

    public String getSupersetRedirectUri() { return supersetRedirectUri; }
    public void setSupersetRedirectUri(String supersetRedirectUri) { this.supersetRedirectUri = supersetRedirectUri; }

    public Map<String, InternalClient> getInternalClients() { return internalClients; }
    public void setInternalClients(Map<String, InternalClient> internalClients) {
        this.internalClients = (internalClients != null) ? internalClients : new LinkedHashMap<>();
    }

    public DirectLogin getDirectLogin() { return directLogin; }
    public void setDirectLogin(DirectLogin directLogin) {
        this.directLogin = (directLogin != null) ? directLogin : new DirectLogin();
    }

    public String getBootstrapAdminPassword() { return bootstrapAdminPassword; }
    public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    public Saml getSaml() { return saml; }
    public void setSaml(Saml saml) { this.saml = (saml != null) ? saml : new Saml(); }

    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = (session != null) ? session : new Session(); }

    public AuthorizationRetention getAuthorizationRetention() { return authorizationRetention; }
    public void setAuthorizationRetention(AuthorizationRetention authorizationRetention) {
        this.authorizationRetention = (authorizationRetention != null)
                ? authorizationRetention : new AuthorizationRetention();
    }

    /**
     * Credential-carrying record for a single internal service caller. A blank
     * secret disables registration for that client, so the default (empty map)
     * leaves the behaviour unchanged for environments that haven't opted in yet.
     */
    public static class InternalClient {
        private String secret;
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }

    /**
     * SAML 2.0 service-provider settings. The platform acts as ONE SAML SP across
     * all tenants; each tenant's IdP config (entity ID, SSO URL, signing cert) is
     * stored per-tenant in the worker's {@code saml_provider} table and loaded at
     * runtime. The SP <em>signing</em> keypair below is platform-wide — it signs
     * outbound AuthnRequests and is published in the SP metadata that admins
     * register at their IdP.
     *
     * <p>Both PEM values are injected from the environment (the private key is a
     * secret). When either is blank, SP request signing is disabled and
     * AuthnRequests are sent unsigned — usable with IdPs that don't require signed
     * requests, but {@code /saml2/metadata} then advertises no signing cert.
     */
    public static class Saml {
        /** SP signing certificate, PEM ({@code -----BEGIN CERTIFICATE-----}). */
        private String spSigningCertificate;
        /** SP signing private key, PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}). Secret. */
        private String spSigningPrivateKey;

        public String getSpSigningCertificate() { return spSigningCertificate; }
        public void setSpSigningCertificate(String spSigningCertificate) {
            this.spSigningCertificate = spSigningCertificate;
        }

        public String getSpSigningPrivateKey() { return spSigningPrivateKey; }
        public void setSpSigningPrivateKey(String spSigningPrivateKey) {
            this.spSigningPrivateKey = spSigningPrivateKey;
        }
    }

    /**
     * The HTTP session cookie and the signed-in idle timeout. Sessions are stored in Redis
     * ({@code io.kelta.auth.session.HttpSessionConfig}); the idle timeout of a session that has
     * not signed in yet is {@code spring.session.timeout}.
     */
    public static class Session {
        /** Idle timeout once the session holds a signed-in user. */
        private Duration authenticatedTimeout = Duration.ofHours(8);
        /** Name of the session cookie. */
        private String cookieName = "kelta_auth_session";
        /**
         * {@code SameSite} attribute of the session cookie: {@code None}, {@code Lax},
         * {@code Strict}, or blank for none. {@code None} lets an identity provider's
         * cross-site POST back to the SAML assertion consumer service carry the session that
         * started the login. When the cookie is sent without {@code Secure} (see
         * {@link #cookieSecure}) {@code None} becomes {@code Lax}, since browsers reject
         * {@code SameSite=None} without {@code Secure}.
         */
        private String cookieSameSite = "None";
        /**
         * Whether the session cookie (and the {@code kelta_session} SSO cookie) is
         * {@code Secure}: {@code auto} (the default) follows the scheme the browser used — the
         * first {@code X-Forwarded-Proto} (or {@code Forwarded: proto=}) value when a proxy sent
         * one, else the request's own scheme; {@code always}; or {@code never}.
         * {@link CookieDomainResolver#secureForRequest}.
         */
        private String cookieSecure = "auto";

        public Duration getAuthenticatedTimeout() { return authenticatedTimeout; }
        public void setAuthenticatedTimeout(Duration authenticatedTimeout) {
            this.authenticatedTimeout = authenticatedTimeout;
        }

        public String getCookieName() { return cookieName; }
        public void setCookieName(String cookieName) { this.cookieName = cookieName; }

        public String getCookieSameSite() { return cookieSameSite; }
        public void setCookieSameSite(String cookieSameSite) { this.cookieSameSite = cookieSameSite; }

        public String getCookieSecure() { return cookieSecure; }
        public void setCookieSecure(String cookieSecure) { this.cookieSecure = cookieSecure; }
    }

    /**
     * Deleting {@code oauth2_authorization} rows whose tokens have all expired
     * ({@code io.kelta.auth.service.AuthorizationRetentionSweep}). Spring Authorization Server
     * never deletes a row itself.
     */
    public static class AuthorizationRetention {
        /** Run the sweep at all. */
        private boolean enabled = true;
        /** Delay between the end of one sweep and the start of the next. */
        private Duration interval = Duration.ofHours(1);
        /** How long after its last token expired a row is kept. */
        private Duration grace = Duration.ofDays(1);
        /** Rows deleted per statement. */
        private int batchSize = 500;
        /** Statements per sweep; a larger backlog drains over several sweeps. */
        private int maxBatches = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }

        public Duration getGrace() { return grace; }
        public void setGrace(Duration grace) { this.grace = grace; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public int getMaxBatches() { return maxBatches; }
        public void setMaxBatches(int maxBatches) { this.maxBatches = maxBatches; }
    }

    /**
     * Direct-login endpoint toggle. Evaluated at request time so the controller
     * stays registered in GraalVM native images (Spring AOT prunes beans whose
     * {@code @ConditionalOnProperty} is unset at build time).
     */
    public static class DirectLogin {
        private boolean enabled;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
