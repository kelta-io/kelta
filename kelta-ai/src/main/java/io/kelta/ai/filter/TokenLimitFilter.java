package io.kelta.ai.filter;

import io.kelta.ai.service.TokenTrackingService;
import io.kelta.runtime.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Pre-checks AI token quota before processing chat requests.
 * Returns HTTP 429 if the tenant has exceeded their monthly token limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TokenLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenLimitFilter.class);

    private final TokenTrackingService tokenTrackingService;

    public TokenLimitFilter(TokenTrackingService tokenTrackingService) {
        this.tokenTrackingService = tokenTrackingService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only filter chat endpoints, not config or history endpoints. Test the path as Spring MVC
        // matches it (segments percent-decoded, path parameters and empty segments dropped), so
        // every spelling that reaches a chat mapping is filtered.
        return !matchedPath(request.getRequestURI()).startsWith("/api/ai/chat");
    }

    /** {@code rawPath} as the handler mappings match it; the raw path when it cannot be decoded. */
    static String matchedPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        StringBuilder matched = new StringBuilder(rawPath.length());
        try {
            for (PathContainer.Element element : PathContainer.parsePath(rawPath).elements()) {
                if (element instanceof PathContainer.PathSegment segment && !segment.valueToMatch().isEmpty()) {
                    matched.append('/').append(segment.valueToMatch());
                }
            }
        } catch (IllegalArgumentException e) {
            // Malformed percent-encoding: no handler mapping matches such a path either.
            return rawPath;
        }
        return matched.isEmpty() ? "/" : matched.toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!tokenTrackingService.isAiEnabled(tenantId)) {
            log.info("AI disabled for tenant {}", tenantId);
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                {"errors":[{"status":"403","code":"AI_DISABLED","title":"AI features are not enabled for this tenant"}]}
                """);
            return;
        }

        if (tokenTrackingService.isTokenLimitExceeded(tenantId)) {
            log.info("Token limit exceeded for tenant {}", tenantId);
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                {"errors":[{"status":"429","code":"TOKEN_LIMIT_EXCEEDED","title":"Monthly AI token limit has been reached"}]}
                """);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
