package io.kelta.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} methods (the {@code oauth2_authorization} retention sweep).
 *
 * <p>Every replica runs each schedule; a scheduled job here must be safe to run on several pods
 * at once (as kelta-worker's sweeps are), since there is no cluster-wide lock.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
