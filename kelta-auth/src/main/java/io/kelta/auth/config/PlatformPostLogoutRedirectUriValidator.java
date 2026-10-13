package io.kelta.auth.config;

import io.kelta.auth.service.AuthDomainResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * {@code post_logout_redirect_uri} validator for the OIDC end-session endpoint
 * ({@code /connect/logout}), the logout counterpart of {@link PlatformRedirectUriValidator}.
 * <p>
 * Spring Authorization Server accepts a post-logout redirect URI only when it exactly matches
 * one registered on the client, and rejects anything else before ending the session. The
 * platform UI ({@code kelta-platform}) signs out to a tenant-scoped URI on its own origin —
 * {@code {ui-origin}/{tenant-slug}?logged_out=true}, or a verified tenant custom domain — which
 * cannot be enumerated at registration time. For that client only, this validator accepts a URI
 * whose origin the client is already trusted to receive authorization codes on:
 * <ul>
 *   <li>the origin of one of its registered redirect or post-logout redirect URIs, or</li>
 *   <li>an {@code https} origin whose host is a verified tenant custom domain
 *       ({@link AuthDomainResolver}).</li>
 * </ul>
 * Any path and query are allowed on such an origin. The URI must be absolute {@code http(s)}
 * with no userinfo and no fragment. Every other client keeps Spring's exact-match rule.
 * <p>
 * A rejection never carries the supplied URI: the endpoint answers 400 and does not redirect.
 */
public class PlatformPostLogoutRedirectUriValidator implements Consumer<OidcLogoutAuthenticationContext> {

    private static final Logger log = LoggerFactory.getLogger(PlatformPostLogoutRedirectUriValidator.class);

    static final String PLATFORM_CLIENT_ID = "kelta-platform";

    private static final String ERROR_URI =
            "https://openid.net/specs/openid-connect-rpinitiated-1_0.html#ValidationAndErrorHandling";

    private final AuthDomainResolver domainResolver;

    public PlatformPostLogoutRedirectUriValidator(AuthDomainResolver domainResolver) {
        this.domainResolver = domainResolver;
    }

    @Override
    public void accept(OidcLogoutAuthenticationContext context) {
        OidcLogoutAuthenticationToken authentication = context.getAuthentication();
        String requested = authentication.getPostLogoutRedirectUri();
        if (requested == null || requested.isBlank()) {
            // No redirect requested — the endpoint falls back to its default landing.
            return;
        }

        RegisteredClient client = context.getRegisteredClient();
        if (client.getPostLogoutRedirectUris().contains(requested)) {
            return;
        }

        if (PLATFORM_CLIENT_ID.equals(client.getClientId()) && isTrustedPlatformTarget(requested, client)) {
            return;
        }

        // Same error Spring's default validator raises; the description names the parameter,
        // never its value.
        throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST,
                "OpenID Connect 1.0 Logout Request Parameter: post_logout_redirect_uri", ERROR_URI));
    }

    private boolean isTrustedPlatformTarget(String requested, RegisteredClient client) {
        URI uri = parse(requested);
        if (uri == null) {
            return false;
        }
        String origin = origin(uri);
        boolean registeredOrigin = Stream.concat(
                        client.getRedirectUris().stream(), client.getPostLogoutRedirectUris().stream())
                .map(PlatformPostLogoutRedirectUriValidator::parse)
                .filter(Objects::nonNull)
                .map(PlatformPostLogoutRedirectUriValidator::origin)
                .anyMatch(origin::equals);
        if (registeredOrigin) {
            log.debug("Allowing post-logout redirect on a registered origin: {}", origin);
            return true;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())
                && domainResolver.resolveTenantSlug(uri.getHost()).isPresent()) {
            log.debug("Allowing post-logout redirect on a verified custom domain: {}", origin);
            return true;
        }
        return false;
    }

    /**
     * Parses an absolute {@code http(s)} URI with a host, no userinfo and no fragment;
     * {@code null} for anything else. {@link URI} rejects backslashes, whitespace and control
     * characters outright, so those never reach the origin comparison.
     */
    private static URI parse(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** {@code scheme://host[:port]}, lower-cased, with the scheme's default port elided. */
    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }
}
