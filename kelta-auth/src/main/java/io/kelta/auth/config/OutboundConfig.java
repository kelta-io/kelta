package io.kelta.auth.config;

import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * kelta-auth's {@link OutboundUrlGuard}, used for login-time OIDC discovery of a
 * tenant-configured issuer ({@code OidcDiscoveryService}) and for federated login's calls to the
 * provider's token, user-info and JWKS endpoints ({@code OidcLoginHttp},
 * {@code DynamicClientRegistrationRepository}).
 *
 * <p>Unconditional on purpose: the allow-list is read at run time from
 * {@code kelta.outbound.allowed-private-cidrs} (declared in {@code application.yml} as
 * {@code ${KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS:}}, the variable the worker reads too), so the
 * native image needs no build-time condition. A malformed entry fails startup.
 */
@Configuration
public class OutboundConfig {

    @Bean
    public OutboundUrlGuard outboundUrlGuard(
            @Value("${" + OutboundUrlGuard.ALLOWED_CIDRS_PROPERTY + ":}") String allowedPrivateCidrs) {
        return new OutboundUrlGuard(allowedPrivateCidrs);
    }
}
