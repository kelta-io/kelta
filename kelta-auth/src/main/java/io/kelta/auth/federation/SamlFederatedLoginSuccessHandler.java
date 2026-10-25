package io.kelta.auth.federation;

import io.kelta.auth.model.KeltaUserDetails;
import io.kelta.auth.model.SamlLogoutInfo;
import io.kelta.auth.service.WorkerClient;
import io.kelta.auth.service.WorkerClient.SamlProviderInfo;
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
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Optional;

/**
 * Handles a successful SAML 2.0 login by mapping the validated assertion to an
 * internal {@link KeltaUserDetails} and replacing the authentication principal so
 * the Spring Authorization Server mints platform tokens — the SAML mirror of
 * {@link FederatedLoginSuccessHandler}.
 *
 * <p>The relying-party {@code registrationId} ({@code tenantId:providerId}) is
 * carried on the {@link Saml2AuthenticatedPrincipal}; it yields the tenant and
 * the provider config (for attribute mappings) used during JIT provisioning.
 */
public class SamlFederatedLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(SamlFederatedLoginSuccessHandler.class);

    private final FederatedUserMapper userMapper;
    private final WorkerClient workerClient;
    private final AuthenticationSuccessHandler delegate;

    public SamlFederatedLoginSuccessHandler(FederatedUserMapper userMapper, WorkerClient workerClient) {
        this.userMapper = userMapper;
        this.workerClient = workerClient;
        this.delegate = new SavedRequestAwareAuthenticationSuccessHandler();
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        if (!(authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal principal)) {
            log.warn("SAML authentication principal is not a Saml2AuthenticatedPrincipal");
            delegate.onAuthenticationSuccess(request, response, authentication);
            return;
        }

        String registrationId = principal.getRelyingPartyRegistrationId();
        log.info("SAML login success: registrationId={} nameId={}", registrationId, principal.getName());

        // Preserve the SAML subject identity (NameID + SessionIndexes) in the HTTP
        // session BEFORE we replace the Saml2Authentication with a platform token —
        // an SP-initiated LogoutRequest (Single Logout) needs them, and the
        // replacement below discards them. Non-SAML sessions never carry this.
        if (registrationId != null) {
            SamlLogoutInfo logoutInfo = new SamlLogoutInfo(
                    registrationId, principal.getName(), principal.getSessionIndexes());
            request.getSession(true).setAttribute(SamlLogoutInfo.SESSION_ATTRIBUTE, logoutInfo);
        }

        // registrationId format: tenantId:providerId
        String[] parts = registrationId == null ? new String[0] : registrationId.split(":", 2);
        if (parts.length != 2) {
            log.error("Invalid SAML registration ID format: {}", registrationId);
            response.sendRedirect("/login?federation");
            return;
        }

        String tenantId = parts[0];
        String providerId = parts[1];

        Optional<SamlProviderInfo> providerOpt = workerClient.findActiveSamlProviders(tenantId).stream()
                .filter(p -> providerId.equals(p.id()))
                .findFirst();

        if (providerOpt.isEmpty()) {
            log.error("SAML provider not found for registration={} tenant={}", registrationId, tenantId);
            response.sendRedirect("/login?federation");
            return;
        }

        Optional<KeltaUserDetails> userDetailsOpt =
                userMapper.mapSamlUser(principal, tenantId, providerOpt.get());

        if (userDetailsOpt.isEmpty()) {
            log.warn("SAML user mapping failed or user pending activation: nameId={}", principal.getName());
            response.sendRedirect("/login?pending_activation");
            return;
        }

        KeltaUserDetails userDetails = userDetailsOpt.get();

        // Replace the authentication with a UsernamePasswordAuthenticationToken
        // wrapping KeltaUserDetails so the token customizer can access it. It keeps the
        // SAML login's factor (or FACTOR_SAML_RESPONSE) for the ID token's auth_time.
        UsernamePasswordAuthenticationToken platformAuth = SignInAuthentication.authenticated(
                userDetails, authentication, FactorGrantedAuthority.SAML_RESPONSE_AUTHORITY);

        SecurityContextHolder.getContext().setAuthentication(platformAuth);

        log.info("SAML user mapped: email={} tenant={} profile={}",
                userDetails.getEmail(), tenantId, userDetails.getProfileId());

        delegate.onAuthenticationSuccess(request, response, platformAuth);
    }
}
