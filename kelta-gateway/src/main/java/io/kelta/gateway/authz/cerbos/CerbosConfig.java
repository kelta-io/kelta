package io.kelta.gateway.authz.cerbos;

import dev.cerbos.sdk.CerbosBlockingClient;
import dev.cerbos.sdk.KeltaCerbosClients;
import dev.cerbos.sdk.builders.Principal;
import dev.cerbos.sdk.builders.Resource;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.concurrent.TimeUnit;

@Configuration
public class CerbosConfig {

    private static final Logger log = LoggerFactory.getLogger(CerbosConfig.class);

    /** Max attempts to warm up the Cerbos gRPC channel on startup. */
    private static final int WARMUP_MAX_ATTEMPTS = 10;

    /** Delay (ms) between warmup retries. */
    private static final long WARMUP_RETRY_DELAY_MS = 1000;

    /** Per-call gRPC deadline for Cerbos checks. */
    static final long CHECK_TIMEOUT_MILLIS = 2000;

    @Value("${kelta.gateway.cerbos.host:cerbos.emf.svc.cluster.local}")
    private String host;

    @Value("${kelta.gateway.cerbos.grpc-port:3593}")
    private int grpcPort;

    @Value("${kelta.gateway.cerbos.keepalive-time-seconds:60}")
    private long keepaliveTimeSeconds;

    @Value("${kelta.gateway.cerbos.keepalive-timeout-seconds:10}")
    private long keepaliveTimeoutSeconds;

    @Value("${kelta.gateway.cerbos.keepalive-without-calls:true}")
    private boolean keepaliveWithoutCalls;

    private CerbosBlockingClient cerbosClient;
    private CerbosChannel cerbosChannel;

    @Bean(destroyMethod = "shutdown")
    public CerbosChannel cerbosChannel() {
        String target = host + ":" + grpcPort;
        log.info("Connecting to Cerbos at {} (gRPC, plaintext, keepalive {}s/{}s, without calls: {})",
                target, keepaliveTimeSeconds, keepaliveTimeoutSeconds, keepaliveWithoutCalls);
        this.cerbosChannel = new CerbosChannel(() -> configureChannel(
                ManagedChannelBuilder.forTarget(target),
                keepaliveTimeSeconds, keepaliveTimeoutSeconds, keepaliveWithoutCalls).build());
        return this.cerbosChannel;
    }

    @Bean
    public CerbosBlockingClient cerbosBlockingClient(CerbosChannel cerbosChannel) {
        this.cerbosClient = KeltaCerbosClients.blockingClient(cerbosChannel, CHECK_TIMEOUT_MILLIS);
        return this.cerbosClient;
    }

    /**
     * Applies plaintext transport and HTTP/2 keepalive. Without keepalive a transport whose
     * peer silently vanished (dropped flow, conntrack loss) keeps receiving calls that hang
     * until their deadline, for as long as the kernel takes to give up on the socket.
     */
    static <T extends ManagedChannelBuilder<T>> T configureChannel(ManagedChannelBuilder<T> builder,
                                                                   long keepaliveTimeSeconds,
                                                                   long keepaliveTimeoutSeconds,
                                                                   boolean keepaliveWithoutCalls) {
        return builder
                .usePlaintext()
                .keepAliveTime(keepaliveTimeSeconds, TimeUnit.SECONDS)
                .keepAliveTimeout(keepaliveTimeoutSeconds, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(keepaliveWithoutCalls);
    }

    /**
     * Warms up the Cerbos gRPC channel after application startup.
     * Makes a lightweight check call to establish the connection before
     * real traffic arrives, preventing cold-start timeouts that would
     * trigger the circuit breaker.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCerbosConnection() {
        if (cerbosClient == null || cerbosChannel == null) {
            log.warn("Cerbos client not initialized — skipping warmup");
            return;
        }
        if (!warmUp(cerbosClient, cerbosChannel, WARMUP_MAX_ATTEMPTS, WARMUP_RETRY_DELAY_MS)) {
            log.error("Cerbos warmup failed after {} attempts — first real requests may trigger circuit breaker",
                    WARMUP_MAX_ATTEMPTS);
        }
    }

    /**
     * Retries a warmup check until one succeeds. After every failed attempt the channel is
     * rebuilt, so a transport that was dialled before the pod's network path was ready is
     * never the one that carries real traffic.
     */
    static boolean warmUp(CerbosBlockingClient client, CerbosChannel channel, int maxAttempts, long retryDelayMs) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // The result doesn't matter — we just need the connection warm.
                Principal principal = Principal.newInstance("warmup@system", "system");
                Resource resource = Resource.newInstance("system_feature", "warmup");
                client.check(principal, resource, "warmup");
                log.info("Cerbos gRPC channel warmed up successfully on attempt {}", attempt);
                return true;
            } catch (Exception e) {
                log.warn("Cerbos warmup attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                channel.rebuild();
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Cerbos warmup interrupted");
                        return false;
                    }
                }
            }
        }
        return false;
    }
}
