package io.kelta.auth.session;

import io.kelta.auth.config.AuthProperties;
import io.kelta.auth.config.CookieDomainResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.session.autoconfigure.SessionTimeout;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.DelegatingIndexResolver;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.IndexResolver;
import org.springframework.session.Session;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

import java.time.Duration;
import java.util.Objects;

/**
 * HTTP sessions in Redis, through Spring Session.
 *
 * <p>Spring Boot's Spring Session auto-configuration creates the store and the session filter
 * from {@code spring.session.*} in {@code application.yml} (indexed Redis repository, namespace,
 * idle timeout of a session that has not signed in). This class supplies the parts that are
 * specific to this service:
 * <ul>
 *   <li>the value serializer ({@link SessionAttributeSerializer});</li>
 *   <li>the cookie ({@link SessionCookieSerializer}, settings under {@code kelta.auth.session});</li>
 *   <li>the per-user session index and the OIDC {@link SessionRegistry} over it
 *       ({@link SessionPrincipalIndexResolver}, {@link TenantScopedSessionRegistry});</li>
 *   <li>the longer idle timeout once signed in ({@link SignedInSessionTimeoutFilter}).</li>
 * </ul>
 *
 * <p>Every replica reads and writes the same sessions, and they survive a restart or rollout. A
 * session cookie the store does not know (a session that has expired, or one from before the
 * store existed) simply starts a new session.
 */
@Configuration(proxyBeanMethods = false)
public class HttpSessionConfig {

    /** Fallback when {@code spring.session.timeout} is unset (the servlet container's default). */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    /**
     * Picked up by name by Spring Session's Redis configuration as the serializer for session
     * values (and for the session-created event it publishes).
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new SessionAttributeSerializer(HttpSessionConfig.class.getClassLoader());
    }

    @Bean
    public CookieSerializer sessionCookieSerializer(AuthProperties properties, CookieDomainResolver hosts) {
        AuthProperties.Session session = properties.getSession();
        return new SessionCookieSerializer(session.getCookieName(), session.getCookieSameSite(), hosts);
    }

    /** Used by the indexed Redis repository in place of its default (login-name, SpEL) resolver. */
    @Bean
    public IndexResolver<Session> sessionIndexResolver() {
        return new DelegatingIndexResolver<>(new SessionPrincipalIndexResolver());
    }

    /**
     * The registry Spring Authorization Server uses for the ID token's {@code sid} and the
     * end-session {@code sid} check (it adopts a {@link SessionRegistry} bean in place of its
     * private in-memory default).
     */
    @Bean
    public SessionRegistry sessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions) {
        return registryOver(sessions);
    }

    @Bean
    public FilterRegistrationBean<SignedInSessionTimeoutFilter> signedInSessionTimeoutFilter(
            AuthProperties properties, ObjectProvider<SessionTimeout> sessionTimeout) {
        Duration defaultTimeout = sessionTimeout.stream()
                .map(SessionTimeout::getTimeout)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(DEFAULT_TIMEOUT);
        FilterRegistrationBean<SignedInSessionTimeoutFilter> registration = new FilterRegistrationBean<>(
                new SignedInSessionTimeoutFilter(properties.getSession().getAuthenticatedTimeout(), defaultTimeout));
        // Directly inside Spring Session's filter (spring.session.servlet.filter-order default),
        // so the session it adjusts is the stored one and is saved when that filter commits it.
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
        return registration;
    }

    static <S extends Session> SessionRegistry registryOver(FindByIndexNameSessionRepository<S> sessions) {
        return new TenantScopedSessionRegistry<>(sessions);
    }
}
