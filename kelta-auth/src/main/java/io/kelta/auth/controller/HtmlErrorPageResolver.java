package io.kelta.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Answers an exception from a page handler (login, consent, MFA, password-change and portal
 * pages) with the HTML error page ({@link ErrorPages}) and the exception's status.
 *
 * <p>Runs before the MVC exception handlers, which include the platform's JSON
 * {@code GlobalExceptionHandler} (runtime-core): that advice answers every exception with a JSON
 * body, which is no answer for a browser on a sign-in page. Exceptions from the JSON endpoints
 * are left to it ({@code null}).
 *
 * <p>A page controller here must not rely on its own {@code @ExceptionHandler} methods: this
 * resolver answers first.
 */
@Component
public class HtmlErrorPageResolver implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(HtmlErrorPageResolver.class);

    /** After Spring Boot's {@code DefaultErrorAttributes} (records the error), before MVC's resolvers (order 0). */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        if (response.isCommitted() || !ErrorPages.wantsHtml(request, response, handler)) {
            return null;
        }
        HttpStatusCode status = ErrorPages.statusFor(ex);
        String requestId = ErrorPages.newRequestId();
        if (status.is5xxServerError()) {
            log.error("Unexpected error [requestId={}] {} {}: {}", requestId, request.getMethod(),
                    request.getRequestURI(), ex.toString(), ex);
        } else {
            log.warn("Request failed [requestId={}, status={}] {} {}: {}", requestId, status.value(),
                    request.getMethod(), request.getRequestURI(), ex.toString());
        }
        return ErrorPages.page(status, requestId);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
