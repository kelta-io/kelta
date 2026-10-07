package io.kelta.gateway.authz.cerbos;

import dev.cerbos.sdk.builders.AttributeValue;
import dev.cerbos.sdk.builders.Principal;
import io.kelta.gateway.auth.GatewayPrincipal;

import java.util.List;
import java.util.stream.Collectors;

public class CerbosPrincipalBuilder {

    private CerbosPrincipalBuilder() {}

    /**
     * {@code P.id} stays the username (the email); {@code P.attr.userId} is the caller's
     * {@code platform_user.id} UUID — the value own-record rules compare UUID columns with — or
     * {@code ""} when the gateway has not resolved it (e.g. a connected app).
     */
    public static Principal build(GatewayPrincipal principal) {
        Principal builder = Principal.newInstance(principal.getUsername(), "user")
                .withAttribute("profileId", stringAttr(principal.getProfileId()))
                .withAttribute("tenantId", stringAttr(principal.getTenantId()))
                .withAttribute("userId", stringAttr(principal.getUserId()))
                .withAttribute("profileName", stringAttr(principal.getProfileName()))
                // Empty when the request origin has no geolocation (private IP, no geo DB) —
                // geo-aware policy rules must handle "".
                .withAttribute("geoCountry", stringAttr(principal.getGeoCountry()));

        List<String> groups = principal.getGroups();
        if (groups != null && !groups.isEmpty()) {
            builder = builder.withAttribute("groups",
                    AttributeValue.listValue(
                            groups.stream()
                                    .map(AttributeValue::stringValue)
                                    .collect(Collectors.toList())));
        }

        return builder;
    }

    private static AttributeValue stringAttr(String value) {
        return AttributeValue.stringValue(value != null ? value : "");
    }
}
