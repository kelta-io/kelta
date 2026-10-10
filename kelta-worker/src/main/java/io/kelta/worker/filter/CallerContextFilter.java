package io.kelta.worker.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.router.UserIdResolver;
import io.kelta.worker.repository.BootstrapRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Binds {@link CallerContext} once per request, so everything downstream (the owner-scope guard,
 * Cerbos principals, owner scoping) reads one resolved identity instead of re-resolving
 * {@code X-User-Id}.
 *
 * <p>Inputs are the gateway-stamped headers only ({@code IdentityHeaderStripFilter} drops
 * client copies): {@code X-User-Id} (an email, or the UUID itself) resolved through
 * {@link UserIdResolver}; {@code X-User-Type}; and {@code X-User-Profile-Id}, whose
 * {@code VIEW_ALL_DATA} / {@code MODIFY_ALL_DATA} grants set the bypass flags. A PORTAL caller
 * never gets the bypass flags, whatever their profile grants.
 *
 * <ul>
 *   <li>No {@code X-User-Id}, or no tenant → nothing is bound (internal tier).</li>
 *   <li>An email that resolves to no {@code platform_user} in the tenant → <b>401
 *       {@code CALLER_UNRESOLVED}</b>. Fail closed: a caller that cannot be identified must not
 *       be mistaken for the internal tier.</li>
 *   <li>An identifier that is neither a UUID nor an email is a machine identity (a connected
 *       app's client id, stamped from the token {@code sub}) — nothing is bound, exactly as
 *       before this filter existed.</li>
 * </ul>
 *
 * <p>Ordered after {@link TenantContextFilter} (needs the tenant to resolve the user).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 12)
public class CallerContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CallerContextFilter.class);

    static final String USER_ID_HEADER = "X-User-Id";
    static final String USER_TYPE_HEADER = "X-User-Type";
    static final String PROFILE_ID_HEADER = "X-User-Profile-Id";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Short so a revoked {@code *_ALL_DATA} grant stops bypassing owner scoping quickly; long
     * enough that a burst of requests from one profile costs one query.
     */
    private static final Duration GRANTS_TTL = Duration.ofSeconds(15);

    private final UserIdResolver userIdResolver;
    private final BootstrapRepository bootstrapRepository;
    private final Cache<String, Set<String>> grantsCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(GRANTS_TTL)
            .build();

    public CallerContextFilter(UserIdResolver userIdResolver, BootstrapRepository bootstrapRepository) {
        this.userIdResolver = userIdResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String identifier = request.getHeader(USER_ID_HEADER);
        String tenantId = TenantContext.get();
        if (isBlank(identifier) || isBlank(tenantId)) {
            filterChain.doFilter(request, response);
            return;
        }
        identifier = identifier.trim();
        if (!isUuid(identifier) && identifier.indexOf('@') < 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = userIdResolver.resolve(identifier, tenantId);
        if (!isUuid(userId)) {
            log.warn("Rejecting request: caller '{}' does not resolve to a user in tenant {}",
                    identifier, tenantId);
            unauthorized(response);
            return;
        }

        UserType userType = UserType.parse(request.getHeader(USER_TYPE_HEADER));
        Set<String> grants = userType == UserType.PORTAL
                ? Set.of()
                : systemGrants(tenantId, request.getHeader(PROFILE_ID_HEADER));
        CallerContext caller = new CallerContext(userId, userType,
                grants.contains("VIEW_ALL_DATA"), grants.contains("MODIFY_ALL_DATA"));

        AtomicReference<IOException> ioErr = new AtomicReference<>();
        AtomicReference<ServletException> servletErr = new AtomicReference<>();
        CallerContext.runAs(caller, () -> {
            try {
                filterChain.doFilter(request, response);
            } catch (IOException e) {
                ioErr.set(e);
            } catch (ServletException e) {
                servletErr.set(e);
            }
        });
        if (ioErr.get() != null) {
            throw ioErr.get();
        }
        if (servletErr.get() != null) {
            throw servletErr.get();
        }
    }

    private Set<String> systemGrants(String tenantId, String profileId) {
        if (isBlank(profileId)) {
            return Set.of();
        }
        return grantsCache.get(tenantId + ":" + profileId, key ->
                bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                        .filter(p -> Boolean.TRUE.equals(p.get("granted")))
                        .map(p -> (String) p.get("permission_name"))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"errors":[{"status":"401","code":"CALLER_UNRESOLVED","title":"Unauthorized",\
                "detail":"The authenticated caller does not resolve to a user in this tenant"}]}""");
    }

    private static boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
