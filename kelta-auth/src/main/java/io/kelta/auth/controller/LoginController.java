package io.kelta.auth.controller;

import io.kelta.auth.config.ExpiredLoginFormHandler;
import io.kelta.auth.federation.DynamicClientRegistrationRepository;
import io.kelta.auth.federation.DynamicRelyingPartyRegistrationRepository;
import io.kelta.auth.service.AuthDomainResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final AuthDomainResolver domainResolver;
    private final RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public LoginController(ClientRegistrationRepository clientRegistrationRepository,
                           AuthDomainResolver domainResolver,
                           ObjectProvider<RelyingPartyRegistrationRepository> relyingPartyRepoProvider) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.domainResolver = domainResolver;
        // Present only when SAML federation is wired (FederatedUserMapper available).
        this.relyingPartyRegistrationRepository = relyingPartyRepoProvider.getIfAvailable();
    }

    @GetMapping("/login")
    public String login(Model model, HttpServletRequest request) {
        // Set error model attributes from query params. The template uses model
        // attributes instead of Thymeleaf's ${param} to avoid false positives
        // with Spring Security's request parameter handling.
        if (request.getParameter("pending_activation") != null) {
            model.addAttribute("pendingActivation", true);
        }
        if (request.getParameter("federation") != null) {
            model.addAttribute("federationError", true);
        }

        // Carry the pending /oauth2/authorize request in the form so a submit after the
        // session expired can resume it (ExpiredLoginFormHandler). getRequest only reads
        // the session; the response argument is unused.
        HttpSession session = request.getSession(false);
        if (session != null) {
            String authorizeUrl = ExpiredLoginFormHandler.pendingAuthorizeUrl(
                    requestCache.getRequest(request, null));
            if (authorizeUrl != null) {
                model.addAttribute("authorizeUrl", authorizeUrl);
            }
            if (session.getAttribute(ExpiredLoginFormHandler.SESSION_EXPIRED_ATTR) != null) {
                session.removeAttribute(ExpiredLoginFormHandler.SESSION_EXPIRED_ATTR);
                model.addAttribute("loginExpired", true);
            }
        }

        String idpHint = request.getParameter("idp_hint");
        if (idpHint != null && !idpHint.isBlank()
                && clientRegistrationRepository instanceof DynamicClientRegistrationRepository dynamicRepo) {
            ClientRegistration hinted = dynamicRepo.findByRegistrationId(idpHint);
            if (hinted != null) {
                log.info("Auto-federating login via idp_hint registration={}", idpHint);
                return "redirect:/oauth2/authorization/"
                        + URLEncoder.encode(idpHint, StandardCharsets.UTF_8);
            }
            log.warn("Ignoring idp_hint={} — no matching client registration", idpHint);
        }

        // Resolve tenant from the request to load SSO providers
        String tenantId = resolveTenantId(request);

        if (tenantId != null && clientRegistrationRepository instanceof DynamicClientRegistrationRepository dynamicRepo) {
            List<ClientRegistration> registrations = dynamicRepo.findByTenantId(tenantId);
            List<SsoProviderInfo> providers = registrations.stream()
                    .map(reg -> new SsoProviderInfo(reg.getRegistrationId(), reg.getClientName()))
                    .toList();
            model.addAttribute("ssoProviders", providers);
        }

        // SAML SSO buttons link to /saml2/authenticate/{registrationId}.
        if (tenantId != null
                && relyingPartyRegistrationRepository instanceof DynamicRelyingPartyRegistrationRepository samlRepo) {
            List<SsoProviderInfo> samlProviders = samlRepo.findButtonsByTenantId(tenantId).stream()
                    .map(b -> new SsoProviderInfo(b.registrationId(), b.name()))
                    .toList();
            model.addAttribute("samlProviders", samlProviders);
        }

        return "login";
    }

    private String resolveTenantId(HttpServletRequest request) {
        // Try tenant from session (set during OAuth2 authorize redirect)
        Object tenantId = request.getSession().getAttribute("tenantId");
        if (tenantId instanceof String str && !str.isBlank()) {
            return str;
        }

        // Try tenant from query parameter
        String param = request.getParameter("tenant");
        if (param != null && !param.isBlank()) {
            request.getSession().setAttribute("tenantId", param);
            return param;
        }

        // Fall back to the tenant prefix of idp_hint ({tenantId}:{providerId}) so SSO
        // buttons render even when the auto-federation short-circuit could not match
        // a client registration. Without this, a user hitting /login?idp_hint=...
        // with no prior session sees only the password form and is stuck.
        String hint = request.getParameter("idp_hint");
        if (hint != null && !hint.isBlank()) {
            int colon = hint.indexOf(':');
            if (colon > 0) {
                String hintTenant = hint.substring(0, colon);
                if (!hintTenant.isBlank()) {
                    request.getSession().setAttribute("tenantId", hintTenant);
                    return hintTenant;
                }
            }
        }

        // Custom-domain login: derive tenant from request Host. This is what
        // makes https://acme.com/login render the right SSO buttons without a
        // tenant slug appearing anywhere in the URL.
        String slug = domainResolver.resolveTenantSlug(request.getServerName()).orElse(null);
        if (slug != null) {
            request.getSession().setAttribute("tenantId", slug);
            return slug;
        }

        return null;
    }

    public record SsoProviderInfo(String registrationId, String name) {}
}
