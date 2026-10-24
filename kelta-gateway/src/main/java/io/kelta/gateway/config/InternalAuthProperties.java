package io.kelta.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rollout flag and client credentials for the gateway's OAuth2 client to the worker's
 * {@code /internal/**} endpoints. Read at run time by {@link InternalServiceAuthConfig};
 * a {@code @ConditionalOnProperty} would be frozen by the native image's AOT build.
 *
 * @param enabled      attach a {@code client_credentials} bearer token to {@code /internal/**} calls
 * @param clientId     OAuth2 client id registered in kelta-auth's {@code internal-clients}
 * @param clientSecret matching secret; required only when {@code enabled}
 */
@ConfigurationProperties("kelta.gateway.internal-auth")
public record InternalAuthProperties(
        boolean enabled,
        @DefaultValue("gateway-internal") String clientId,
        String clientSecret) {
}
