package io.kelta.auth.federation;

import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.Response;
import org.springframework.security.saml2.core.OpenSamlInitializationService;

/**
 * Whether OpenSAML initialized in this process, decided once at startup.
 *
 * <p>Spring Security's SAML classes initialize OpenSAML from static initializers, so a
 * library that cannot initialize (for example a native image missing a resource or
 * reflection entry) would otherwise fail the whole application context — taking password
 * login and sign-out down with it. {@link #probe()} initializes OpenSAML first, inside a
 * try/catch, so a failure turns SAML off with a clear error instead of stopping the
 * service. It is a runtime check: nothing about it is frozen into a native image's
 * build-time bean registrations.
 *
 * @param available whether SAML federation can be served
 * @param reason    why it cannot, when {@code available} is false
 */
public record OpenSamlStatus(boolean available, String reason) {

    /** Initializes OpenSAML and checks the SAML 2.0 protocol builders the SP needs are registered. */
    public static OpenSamlStatus probe() {
        try {
            OpenSamlInitializationService.initialize();
            XMLObjectBuilderFactory builders = XMLObjectProviderRegistrySupport.getBuilderFactory();
            if (builders == null
                    || builders.getBuilder(AuthnRequest.DEFAULT_ELEMENT_NAME) == null
                    || builders.getBuilder(Response.DEFAULT_ELEMENT_NAME) == null
                    || builders.getBuilder(LogoutRequest.DEFAULT_ELEMENT_NAME) == null) {
                return unavailable("OpenSAML initialized without the SAML 2.0 protocol object providers");
            }
            return new OpenSamlStatus(true, null);
        } catch (RuntimeException | LinkageError e) {
            return unavailable(e.toString());
        }
    }

    public static OpenSamlStatus unavailable(String reason) {
        return new OpenSamlStatus(false, reason);
    }
}
