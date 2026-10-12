package io.kelta.auth.controller;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.federation.SamlLogoutSupport;
import io.kelta.auth.model.SamlLogoutInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.saml2.provider.service.authentication.logout.Saml2LogoutRequest;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.security.saml2.provider.service.web.authentication.logout.HttpSessionLogoutRequestRepository;
import org.springframework.security.saml2.provider.service.web.authentication.logout.Saml2LogoutRequestRepository;
import org.springframework.security.saml2.provider.service.web.authentication.logout.Saml2LogoutRequestResolver;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * SP-initiated SAML Single Logout initiator.
 *
 * <p>The app's own logout routes here (before the OIDC {@code end_session_endpoint})
 * so a user who signed in via SAML is also logged out at their IdP. Given the SAML
 * subject identity captured at login ({@link SamlLogoutInfo} in the session), this
 * resolves a signed {@code LogoutRequest} and redirects the browser to the IdP's
 * SingleLogoutService. The IdP then posts a {@code LogoutResponse} back to
 * {@code /logout/saml2/slo/{registrationId}} (handled by Spring Security's
 * {@code saml2Logout} filters), whose success handler
 * ({@link io.kelta.auth.federation.SamlLogoutSuccessHandler}) forwards the browser to
 * the stashed {@code post_logout_redirect_uri}.
 *
 * <p><b>Safe passthrough:</b> for a non-SAML session (internal password, OIDC-brokered)
 * — or a SAML provider with no SLO URL configured — there is nothing to send, so the
 * request simply redirects to the (validated) {@code post_logout_redirect_uri}. This
 * lets the frontend route every logout through here without first knowing the login
 * method.
 *
 * <p>The endpoint is {@code permitAll} on the SAML chain: an expired session must
 * still complete logout (fall through to the OIDC end-session), not bounce to /login.
 *
 * <p>It is registered unconditionally ({@link io.kelta.auth.config.FederationConfig}):
 * the UI sends every sign-out here, so it must exist even when SAML or encryption is
 * unavailable — then the resolver yields nothing and every request passes through.
 */
@Controller
public class SamlLogoutController {

    private static final Logger log = LoggerFactory.getLogger(SamlLogoutController.class);

    private final Saml2LogoutRequestResolver logoutRequestResolver;
    private final AuthProperties authProperties;
    private final Saml2LogoutRequestRepository logoutRequestRepository = new HttpSessionLogoutRequestRepository();

    public SamlLogoutController(Saml2LogoutRequestResolver logoutRequestResolver,
                                AuthProperties authProperties) {
        this.logoutRequestResolver = logoutRequestResolver;
        this.authProperties = authProperties;
    }

    @GetMapping("/logout/saml2/initiate")
    public void initiate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String allowedOrigin = SamlLogoutSupport.allowedOrigin(authProperties.getIssuerUri(), request);
        String postLogout = SamlLogoutSupport.sanitizeRedirect(
                request.getParameter("post_logout_redirect_uri"), allowedOrigin);

        HttpSession session = request.getSession(false);
        SamlLogoutInfo info = (session == null) ? null
                : asLogoutInfo(session.getAttribute(SamlLogoutInfo.SESSION_ATTRIBUTE));

        if (info == null) {
            // Not a SAML session — nothing to send to an IdP.
            passthrough(response, postLogout);
            return;
        }

        Saml2LogoutRequest logoutRequest = resolveLogoutRequest(request, info);
        if (logoutRequest == null) {
            // SAML session but the provider advertises no SLO endpoint.
            log.debug("No SAML LogoutRequest resolved for registration {} — passthrough", info.registrationId());
            passthrough(response, postLogout);
            return;
        }

        // Consume the login marker so a re-entry doesn't resend, and stash the
        // post-logout target for the completion handler to redirect to after the
        // IdP round-trip (kept out of RelayState so no token leaks to the IdP).
        session.removeAttribute(SamlLogoutInfo.SESSION_ATTRIBUTE);
        if (postLogout != null) {
            session.setAttribute(SamlLogoutSupport.POST_LOGOUT_URI_ATTRIBUTE, postLogout);
        }

        logoutRequestRepository.saveLogoutRequest(logoutRequest, request, response);
        log.info("Sending SP-initiated SAML LogoutRequest for registration {}", info.registrationId());
        sendLogoutRequest(response, logoutRequest);
    }

    private Saml2LogoutRequest resolveLogoutRequest(HttpServletRequest request, SamlLogoutInfo info) {
        // Rebuild the Saml2Authentication the resolver needs: NameID + SessionIndexes
        // + the relying-party registration id (all captured at login).
        DefaultSaml2AuthenticatedPrincipal principal =
                new DefaultSaml2AuthenticatedPrincipal(info.nameId(), Map.of(), info.sessionIndexes());
        principal.setRelyingPartyRegistrationId(info.registrationId());
        // saml2Response must be non-blank; the resolver reads only the principal
        // (NameID + SessionIndexes + registrationId), so a placeholder suffices.
        Saml2Authentication authentication = new Saml2Authentication(principal, "sp-initiated-logout", List.of());
        try {
            return logoutRequestResolver.resolve(request, authentication);
        } catch (Exception e) {
            log.warn("Failed to resolve SAML LogoutRequest for registration {}: {}",
                    info.registrationId(), e.getMessage());
            return null;
        }
    }

    /** Redirects to the validated post-logout target, or a safe default if absent. */
    private void passthrough(HttpServletResponse response, String postLogout) throws IOException {
        response.sendRedirect(postLogout != null ? postLogout : SamlLogoutSupport.DEFAULT_LOGOUT_SUCCESS_URL);
    }

    private void sendLogoutRequest(HttpServletResponse response, Saml2LogoutRequest logoutRequest)
            throws IOException {
        if (logoutRequest.getBinding() == Saml2MessageBinding.REDIRECT) {
            String location = logoutRequest.getLocation();
            String separator = location.contains("?") ? "&" : "?";
            response.sendRedirect(location + separator + logoutRequest.getParametersQuery());
        } else {
            writePostForm(response, logoutRequest);
        }
    }

    /** Renders a self-submitting HTML form for the SAML HTTP-POST binding. */
    private void writePostForm(HttpServletResponse response, Saml2LogoutRequest logoutRequest)
            throws IOException {
        StringBuilder inputs = new StringBuilder();
        for (Map.Entry<String, String> param : logoutRequest.getParameters().entrySet()) {
            inputs.append("<input type=\"hidden\" name=\"")
                    .append(HtmlUtils.htmlEscape(param.getKey()))
                    .append("\" value=\"")
                    .append(HtmlUtils.htmlEscape(param.getValue()))
                    .append("\"/>");
        }
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head>"
                + "<body onload=\"document.forms[0].submit()\">"
                + "<form method=\"post\" action=\"" + HtmlUtils.htmlEscape(logoutRequest.getLocation()) + "\">"
                + inputs
                + "<noscript><button type=\"submit\">Continue</button></noscript>"
                + "</form></body></html>";
        response.setContentType("text/html;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(html);
    }

    private static SamlLogoutInfo asLogoutInfo(Object attribute) {
        return (attribute instanceof SamlLogoutInfo info) ? info : null;
    }
}
