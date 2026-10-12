package io.kelta.auth.controller;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.service.AuthDomainResolver;
import io.kelta.auth.service.PortalLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Optional;

/**
 * Passwordless portal login pages (telehealth slice 1). External portal users
 * request a magic link by email and are signed in when they open it — there is
 * no password form on this flow, and the standard {@code /login} form cannot
 * authenticate portal users (they have no {@code user_credential} row).
 *
 * <p>Follows the {@code MfaController} page-controller idiom. Tenant is
 * resolved like {@code LoginController}: session attribute → {@code ?tenant=}
 * query parameter → custom-domain Host lookup.
 */
@Controller
public class PortalLoginController {

    private static final Logger log = LoggerFactory.getLogger(PortalLoginController.class);

    private final PortalLoginService portalLoginService;
    private final AuthDomainResolver domainResolver;
    private final AuthProperties authProperties;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public PortalLoginController(PortalLoginService portalLoginService,
                                 AuthDomainResolver domainResolver,
                                 AuthProperties authProperties) {
        this.portalLoginService = portalLoginService;
        this.domainResolver = domainResolver;
        this.authProperties = authProperties;
    }

    @GetMapping("/portal/login")
    public String portalLogin(Model model, HttpServletRequest request) {
        String tenant = resolveTenant(request);
        model.addAttribute("tenantKnown", tenant != null);
        return "portal-login";
    }

    /**
     * Always renders the same confirmation view — whether or not the email
     * matched a portal user — so the endpoint cannot be used to enumerate
     * accounts.
     */
    @PostMapping("/portal/login/request")
    public String requestLink(@RequestParam("email") String email,
                              Model model, HttpServletRequest request) {
        String tenant = resolveTenant(request);
        if (tenant == null) {
            model.addAttribute("tenantKnown", false);
            return "portal-login";
        }
        Optional<String> tenantUuid = portalLoginService.resolveTenantUuid(tenant);
        tenantUuid.ifPresent(uuid -> portalLoginService.requestLink(uuid, email, verifyUrl(request)));
        model.addAttribute("email", email);
        return "portal-login-sent";
    }

    @GetMapping("/portal/login/verify")
    public String verify(@RequestParam("token") String token,
                         HttpServletRequest request, HttpServletResponse response) {
        Optional<PortalLoginService.PortalVerification> verified = portalLoginService.verify(token);
        if (verified.isEmpty()) {
            return "portal-login-error";
        }

        var userDetails = verified.get().userDetails();

        // Establish the authenticated session. Spring Security 6 no longer
        // saves the context implicitly — persist it to the HTTP session so the
        // subsequent /oauth2/authorize redirect sees the authentication.
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        userDetails, null, userDetails.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // Keep the login-flow tenant attribute consistent with form login so
        // downstream pages (consent, session) resolve the same tenant. It must be
        // the slug, as LoginController writes: TenantContextFilter compares it with
        // the slug in the SPA's /oauth2/authorize redirect_uri, and a UUID there
        // reads as a tenant switch that clears the session this method just saved.
        request.getSession().setAttribute("tenantId", verified.get().tenantSlug());

        // A magic link opens in a fresh browser context, so there is no saved
        // /oauth2/authorize request to replay. Send the user to the tenant's
        // end-user app; it initiates the OIDC flow, which now completes
        // silently against the authenticated session.
        String base = authProperties.getUiBaseUrl();
        if (base != null && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String target = base + "/" + verified.get().tenantSlug() + "/app";
        log.debug("Portal login verified for {} — redirecting to app", userDetails.getId());
        return "redirect:" + target;
    }

    /** Absolute URL of the verify endpoint on the current host (custom-domain safe). */
    private String verifyUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath("/portal/login/verify")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    /** Same resolution order as {@code LoginController}: session → ?tenant= → custom domain. */
    private String resolveTenant(HttpServletRequest request) {
        Object session = request.getSession().getAttribute("tenantId");
        if (session instanceof String str && !str.isBlank()) {
            return str;
        }
        String param = request.getParameter("tenant");
        if (param != null && !param.isBlank()) {
            request.getSession().setAttribute("tenantId", param);
            return param;
        }
        String slug = domainResolver.resolveTenantSlug(request.getServerName()).orElse(null);
        if (slug != null) {
            request.getSession().setAttribute("tenantId", slug);
            return slug;
        }
        return null;
    }
}
