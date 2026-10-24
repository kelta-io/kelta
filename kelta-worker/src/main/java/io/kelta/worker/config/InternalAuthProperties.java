package io.kelta.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rollout flag for JWT protection of the worker's {@code /internal/**} endpoints. Read by
 * {@link InternalEndpointSecurityConfig} when it builds the {@code /internal/**} chain.
 *
 * @param enabled require a {@code client_credentials} JWT with scope {@code internal};
 *                off leaves {@code /internal/**} permit-all
 */
@ConfigurationProperties("kelta.worker.internal-auth")
public record InternalAuthProperties(boolean enabled) {
}
