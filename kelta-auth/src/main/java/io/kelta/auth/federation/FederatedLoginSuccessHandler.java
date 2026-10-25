package io.kelta.auth.federation;

import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.WorkerClient.OidcProviderInfo;
import io.kelta.auth.session.SignInAuthentication;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Optional;

/**
 * Handles successful federated OAuth2 login by mapping the external user
 * to an internal {@link KeltaUserDetails} and replacing the authentication
 * principal so the Spring Authorization Server can mint platform tokens.
 *
 * <p>Flow:
 * <ol>
 *   <li>User authenticates with external IdP</li>
 *   <li>This handler receives the OAuth2 authentication token</li>
 *   <li>{@link FederatedUserMapper} maps the external user → internal user (JIT)</li>
 *   <li>Authentication is replaced with a {@link UsernamePasswordAuthenticationToken}
 *       wrapping the {@link KeltaUserDetails}</li>
 *   <li>The authorization code flow continues and mints a platform token</li>
 * </ol>
 */
public class FederatedLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(FederatedLoginSuccessHandler.class);

    private final FederatedUserMapper userMapper;
    private final WorkerClient workerClient;
    private final AuthenticationSuccessHandler delegate;

    public FederatedLoginSuccessHandler(
            FederatedUserMapper userMapper,
            WorkerClient workerClient) {
        this.userMapper = userMapper;
        this.workerClient = workerClient;
        this.delegate = new SavedRequestAwareAuthenticationSuccessHandler();
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            delegate.onAuthenticationSuccess(request, response, authentication);
            return;
        }

        if (!(oauthToken.getPrincipal() instanceof OidcUser oidcUser)) {
            log.warn("OAuth2 authentication principal is not an OidcUser");
            delegate.onAuthenticationSuccess(request, response, authentication);
            return;
        }

        String registrationId = oauthToken.getAuthorizedClientRegistrationId();
        log.info("Federated login success: registrationId={} subject={}",
                registrationId, oidcUser.getSubject());

        // Parse tenantId and providerId from registrationId (format: tenantId:providerId)
        String[] parts = registrationId.split(":", 2);
        if (parts.length != 2) {
            log.error("Invalid registration ID format: {}", registrationId);
            response.sendRedirect("/login?federation");
            return;
        }

        String tenantId = parts[0];
        String providerId = parts[1];

        // Look up the OIDC provider configuration
        Optional<OidcProviderInfo> providerOpt = workerClient.findOidcProviderByIssuer(
                oidcUser.getIssuer().toString(), tenantId);

        if (providerOpt.isEmpty()) {
            log.error("OIDC provider not found for issuer={} tenant={}",
                    oidcUser.getIssuer(), tenantId);
            response.sendRedirect("/login?federation");
            return;
        }

        // Map the external user to an internal user (JIT provisioning)
        Optional<KeltaUserDetails> userDetailsOpt =
                userMapper.mapUser(oidcUser, tenantId, providerOpt.get());

        if (userDetailsOpt.isEmpty()) {
            log.warn("User mapping failed or user pending activation: subject={}",
                    oidcUser.getSubject());
            response.sendRedirect("/login?pending_activation");
            return;
        }

        KeltaUserDetails userDetails = userDetailsOpt.get();

        // Replace the authentication with a UsernamePasswordAuthenticationToken
        // wrapping KeltaUserDetails so the token customizer can access it. It keeps the
        // upstream login's factor (or FACTOR_AUTHORIZATION_CODE — Spring's OIDC login
        // provider adds none, spring-security#19140) for the ID token's auth_time.
        UsernamePasswordAuthenticationToken platformAuth = SignInAuthentication.authenticated(
                userDetails, authentication, FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);

        SecurityContextHolder.getContext().setAuthentication(platformAuth);

        log.info("Federated user mapped: email={} tenant={} profile={}",
                userDetails.getEmail(), tenantId, userDetails.getProfileId());

        delegate.onAuthenticationSuccess(request, response, platformAuth);
    }
}
