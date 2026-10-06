package io.kelta.auth.config;

import io.kelta.auth.service.AuthDomainResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.net.URI;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Custom redirect URI validator for the multi-tenant platform.
 * <p>
 * Spring Authorization Server requires exact redirect_uri matching, but our UI
 * uses tenant-scoped callback URLs: {@code {origin}/{tenant-slug}/auth/callback}.
 * <p>
 * This validator allows any redirect_uri whose origin matches a registered
 * redirect_uri's origin and whose path ends with {@code /auth/callback}.
 */
public class PlatformRedirectUriValidator
        implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    private static final Logger log = LoggerFactory.getLogger(PlatformRedirectUriValidator.class);

    /**
     * Loopback callback path for {@code kelta-cli}: {@code /{tenant-slug}/auth/callback}.
     * The slug shape mirrors the gateway's TenantSlugExtractionFilter so a URI accepted
     * here resolves to the same tenant everywhere else.
     */
    private static final Pattern LOOPBACK_CALLBACK_PATH =
            Pattern.compile("^/[a-z][a-z0-9-]*/auth/callback$");

    private final AuthDomainResolver domainResolver;

    public PlatformRedirectUriValidator(AuthDomainResolver domainResolver) {
        this.domainResolver = domainResolver;
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken authenticationToken =
                context.getAuthentication();
        RegisteredClient registeredClient = context.getRegisteredClient();
        String requestedRedirectUri = authenticationToken.getRedirectUri();

        if (requestedRedirectUri == null || requestedRedirectUri.isBlank()) {
            // No redirect_uri provided — let Spring's default handling resolve it
            return;
        }

        // Check exact match first (standard behavior)
        if (registeredClient.getRedirectUris().contains(requestedRedirectUri)) {
            return;
        }

        // For the platform client, allow any redirect_uri that:
        // 1. Has the same origin as a registered redirect URI
        // 2. Has a path ending with /auth/callback
        if ("kelta-platform".equals(registeredClient.getClientId())) {
            if (isOriginMatchWithSuffix(requestedRedirectUri, registeredClient, "/auth/callback")) {
                return;
            }
            // Also accept any verified tenant custom domain so customer-branded
            // callbacks like https://acme.com/auth/callback work without each
            // origin having to be pre-registered on the platform client.
            if (isVerifiedCustomDomainCallback(requestedRedirectUri)) {
                return;
            }
        }

        // For the CLI client ONLY: RFC 8252 §7.3 loopback redirect. Native apps
        // cannot pre-register a port, so the OS assigns one at login time and the
        // port MUST be ignored during comparison. Everything else stays strict:
        // scheme http, host a loopback IP LITERAL (never "localhost" — a hosts-file
        // or DNS entry can point it anywhere), path exactly /callback, and no
        // userinfo/query/fragment. No other client gets this rule.
        if ("kelta-cli".equals(registeredClient.getClientId())) {
            if (isLoopbackCallback(requestedRedirectUri)) {
                return;
            }
        }

        // For connected apps with multiple redirect URIs, validate that the
        // requested URI matches a registered origin + exact path. This supports
        // apps that register multiple callback paths for different environments.
        if (registeredClient.getRedirectUris().size() > 1) {
            if (isExactOriginAndPathMatch(requestedRedirectUri, registeredClient)) {
                return;
            }
        }

        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST,
                "invalid_redirect_uri", "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1");
        throw new OAuth2AuthorizationCodeRequestAuthenticationException(error,
                withoutRedirectUri(authenticationToken));
    }

    /**
     * The request token with its redirect URI removed, for the rejection.
     *
     * <p>The authorization endpoint sends an error to the token's redirect URI when it has one,
     * and renders a 400 otherwise. RFC 6749 §4.1.2.1: the server MUST NOT redirect to an
     * invalid redirection URI. Spring's own validator clears it the same way.
     */
    private static OAuth2AuthorizationCodeRequestAuthenticationToken withoutRedirectUri(
            OAuth2AuthorizationCodeRequestAuthenticationToken token) {
        return new OAuth2AuthorizationCodeRequestAuthenticationToken(
                token.getAuthorizationUri(), token.getClientId(),
                (Authentication) token.getPrincipal(), null,
                token.getState(), token.getScopes(), token.getAdditionalParameters());
    }

    private boolean isOriginMatchWithSuffix(String requestedRedirectUri,
                                            RegisteredClient registeredClient,
                                            String pathSuffix) {
        try {
            URI requested = URI.create(requestedRedirectUri);
            String requestedOrigin = extractOrigin(requested);

            for (String registered : registeredClient.getRedirectUris()) {
                URI registeredUri = URI.create(registered);
                String registeredOrigin = extractOrigin(registeredUri);

                if (requestedOrigin.equals(registeredOrigin)
                        && requested.getPath() != null
                        && requested.getPath().endsWith(pathSuffix)) {
                    log.debug("Allowing tenant-scoped redirect_uri: {}", requestedRedirectUri);
                    return true;
                }
            }
        } catch (IllegalArgumentException e) {
            // Invalid URI
        }
        return false;
    }

    private boolean isExactOriginAndPathMatch(String requestedRedirectUri,
                                              RegisteredClient registeredClient) {
        try {
            URI requested = URI.create(requestedRedirectUri);
            String requestedOrigin = extractOrigin(requested);

            for (String registered : registeredClient.getRedirectUris()) {
                URI registeredUri = URI.create(registered);
                String registeredOrigin = extractOrigin(registeredUri);

                if (requestedOrigin.equals(registeredOrigin)
                        && requested.getPath() != null
                        && requested.getPath().equals(registeredUri.getPath())) {
                    log.debug("Allowing connected app redirect_uri with origin+path match: {}",
                            requestedRedirectUri);
                    return true;
                }
            }
        } catch (IllegalArgumentException e) {
            // Invalid URI
        }
        return false;
    }

    /**
     * RFC 8252 §7.3 loopback-interface redirect for the {@code kelta-cli} client:
     * {@code http://127.0.0.1:<any port>/<tenant-slug>/auth/callback} (or {@code [::1]}).
     * The port is deliberately not compared; host must be a loopback IP literal
     * (NOT {@code localhost}); and the URI must carry no userinfo, query, or fragment.
     * <p>
     * The path MUST carry the tenant slug in the same shape every other client uses
     * ({@code /{slug}/auth/callback}), because {@link TenantContextFilter} derives the
     * login's tenant from exactly that pattern. A slug-less {@code /callback} is
     * accepted by OAuth but then fails at authentication with "no tenant context in
     * session" — which is precisely the bug this shape prevents.
     */
    private boolean isLoopbackCallback(String requestedRedirectUri) {
        try {
            URI uri = URI.create(requestedRedirectUri);
            boolean loopbackHost = "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
            String path = uri.getPath();
            if ("http".equals(uri.getScheme())
                    && loopbackHost
                    && path != null
                    && LOOPBACK_CALLBACK_PATH.matcher(path).matches()
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null) {
                log.debug("Allowing RFC 8252 loopback redirect_uri for kelta-cli: {}", requestedRedirectUri);
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to false
        }
        return false;
    }

    private boolean isVerifiedCustomDomainCallback(String requestedRedirectUri) {
        try {
            URI uri = URI.create(requestedRedirectUri);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null || !path.endsWith("/auth/callback")) {
                return false;
            }
            if (domainResolver.resolveTenantSlug(host).isPresent()) {
                log.debug("Allowing custom-domain redirect_uri: {}", requestedRedirectUri);
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to false
        }
        return false;
    }

    private static String extractOrigin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }
}
