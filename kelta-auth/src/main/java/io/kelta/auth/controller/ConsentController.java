package io.kelta.auth.controller;

import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renders the OAuth2 consent screen for third-party connected apps.
 * <p>
 * When a connected app requests authorization_code flow and the user has not
 * previously consented to the requested scopes, Spring Authorization Server
 * redirects to this consent page.
 */
@Controller
public class ConsentController {

    private final RegisteredClientRepository registeredClientRepository;
    private final OAuth2AuthorizationConsentService consentService;

    private static final Map<String, ScopeInfo> SCOPE_DESCRIPTIONS = new LinkedHashMap<>();

    static {
        SCOPE_DESCRIPTIONS.put("openid", new ScopeInfo("OpenID Connect", "Verify your identity"));
        SCOPE_DESCRIPTIONS.put("profile", new ScopeInfo("Profile", "Access your name and profile information"));
        SCOPE_DESCRIPTIONS.put("email", new ScopeInfo("Email", "Access your email address"));
        SCOPE_DESCRIPTIONS.put("api", new ScopeInfo("API Access", "Access the platform API on your behalf"));
        SCOPE_DESCRIPTIONS.put("read:records", new ScopeInfo("Read Records", "Read records from collections"));
        SCOPE_DESCRIPTIONS.put("write:records", new ScopeInfo("Write Records", "Create and update records in collections"));
        SCOPE_DESCRIPTIONS.put("delete:records", new ScopeInfo("Delete Records", "Delete records from collections"));
        SCOPE_DESCRIPTIONS.put("read:reports", new ScopeInfo("Read Reports", "Access and run reports"));
        SCOPE_DESCRIPTIONS.put("read:dashboards", new ScopeInfo("Read Dashboards", "View dashboard data"));
        SCOPE_DESCRIPTIONS.put("admin", new ScopeInfo("Administration", "Perform administrative operations"));
    }

    public ConsentController(RegisteredClientRepository registeredClientRepository,
                             OAuth2AuthorizationConsentService consentService) {
        this.registeredClientRepository = registeredClientRepository;
        this.consentService = consentService;
    }

    @GetMapping("/oauth2/consent")
    public String consent(Principal principal, Model model,
                          @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
                          @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
                          @RequestParam(OAuth2ParameterNames.STATE) String state) {

        RegisteredClient registeredClient = registeredClientRepository.findByClientId(clientId);
        if (registeredClient == null) {
            return "redirect:/login?error";
        }

        // Get previously approved scopes (if any)
        OAuth2AuthorizationConsent previousConsent = consentService.findById(
                registeredClient.getId(), principal.getName());
        Set<String> previouslyApprovedScopes = previousConsent != null
                ? previousConsent.getScopes() : Set.of();

        // Parse requested scopes
        Set<String> requestedScopes = new LinkedHashSet<>();
        for (String s : scope.split(" ")) {
            if (!s.isBlank()) {
                requestedScopes.add(s);
            }
        }

        // Build scope display items — separate previously approved from new
        Set<ScopeDisplay> scopesToApprove = new LinkedHashSet<>();
        Set<ScopeDisplay> previouslyApproved = new LinkedHashSet<>();

        for (String requestedScope : requestedScopes) {
            ScopeInfo info = SCOPE_DESCRIPTIONS.getOrDefault(requestedScope,
                    new ScopeInfo(requestedScope, "Access: " + requestedScope));

            if (previouslyApprovedScopes.contains(requestedScope)) {
                previouslyApproved.add(new ScopeDisplay(requestedScope, info.label(), info.description()));
            } else {
                scopesToApprove.add(new ScopeDisplay(requestedScope, info.label(), info.description()));
            }
        }

        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", registeredClient.getClientName());
        model.addAttribute("state", state);
        model.addAttribute("scopes", scopesToApprove);
        model.addAttribute("previouslyApprovedScopes", previouslyApproved);
        // Booleans for the template: `set.isEmpty()` in a template expression needs
        // reflection on the JDK collection class, which the native image does not register.
        model.addAttribute("hasScopes", !scopesToApprove.isEmpty());
        model.addAttribute("hasPreviouslyApprovedScopes", !previouslyApproved.isEmpty());
        model.addAttribute("principalName", principal.getName());

        return "consent";
    }

    public record ScopeInfo(String label, String description) {}
    public record ScopeDisplay(String scope, String label, String description) {}
}
