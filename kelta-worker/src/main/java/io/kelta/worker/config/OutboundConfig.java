package io.kelta.worker.config;

import io.kelta.runtime.outbound.OutboundUrlGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The worker's {@link OutboundUrlGuard}, shared by every fetch of a tenant-supplied URL: OIDC
 * discovery, OpenAPI spec import, the flow HTTP handlers ({@code HTTP_CALLOUT}, {@code CALL_API},
 * {@code OUTBOUND_MESSAGE} — handed to the integration module as a {@code ModuleContext}
 * extension by {@link FlowConfig}), external-rest collections, OAuth 2.0 credential token URLs
 * and Web Push delivery.
 *
 * <p>Unconditional on purpose: the allow-list is read at run time from
 * {@code kelta.outbound.allowed-private-cidrs} (declared in {@code application.yml} as
 * {@code ${KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS:}}), so the native image needs no build-time
 * condition. A malformed entry fails startup.
 */
@Configuration
public class OutboundConfig {

    @Bean
    public OutboundUrlGuard outboundUrlGuard(
            @Value("${" + OutboundUrlGuard.ALLOWED_CIDRS_PROPERTY + ":}") String allowedPrivateCidrs) {
        return new OutboundUrlGuard(allowedPrivateCidrs);
    }
}
