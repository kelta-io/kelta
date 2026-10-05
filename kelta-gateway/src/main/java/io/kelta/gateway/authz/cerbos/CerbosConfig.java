package io.kelta.gateway.authz.cerbos;

import dev.cerbos.sdk.CerbosBlockingClient;
import dev.cerbos.sdk.CerbosClientBuilder;
import dev.cerbos.sdk.builders.Principal;
import dev.cerbos.sdk.builders.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
public class CerbosConfig {

    private static final Logger log = LoggerFactory.getLogger(CerbosConfig.class);

    /** Max attempts to warm up the Cerbos gRPC channel on startup. */
    private static final int WARMUP_MAX_ATTEMPTS = 10;

    /** Delay (ms) between warmup retries. */
    private static final long WARMUP_RETRY_DELAY_MS = 1000;

    @Value("${kelta.gateway.cerbos.host:cerbos.emf.svc.cluster.local}")
    private String host;

    @Value("${kelta.gateway.cerbos.grpc-port:3593}")
    private int grpcPort;

    private CerbosBlockingClient cerbosClient;

    @Bean
    public CerbosBlockingClient cerbosBlockingClient() throws CerbosClientBuilder.InvalidClientConfigurationException {
        String target = host + ":" + grpcPort;
        log.info("Connecting to Cerbos at {} (gRPC, plaintext)", target);
        this.cerbosClient = new CerbosClientBuilder(target)
                .withPlaintext()
                .withTimeout(java.time.Duration.ofSeconds(2))
                .buildBlockingClient();
        return this.cerbosClient;
    }

    /**
     * Warms up the Cerbos gRPC channel after application startup.
     * Makes a lightweight check call to establish the connection before
     * real traffic arrives, preventing cold-start timeouts that would
     * trigger the circuit breaker.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCerbosConnection() {
        if (cerbosClient == null) {
            log.warn("Cerbos client not initialized — skipping warmup");
            return;
        }

        for (int attempt = 1; attempt <= WARMUP_MAX_ATTEMPTS; attempt++) {
            try {
                // Make a lightweight check to establish the gRPC channel.
                // The result doesn't matter — we just need the connection warm.
                Principal principal = Principal.newInstance("warmup@system", "system");
                Resource resource = Resource.newInstance("system_feature", "warmup");
                cerbosClient.check(principal, resource, "warmup");
                log.info("Cerbos gRPC channel warmed up successfully on attempt {}", attempt);
                return;
            } catch (Exception e) {
                log.warn("Cerbos warmup attempt {}/{} failed: {}", attempt, WARMUP_MAX_ATTEMPTS, e.getMessage());
                if (attempt < WARMUP_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(WARMUP_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Cerbos warmup interrupted");
                        return;
                    }
                }
            }
        }
        log.error("Cerbos warmup failed after {} attempts — first real requests may trigger circuit breaker",
                WARMUP_MAX_ATTEMPTS);
    }
}
