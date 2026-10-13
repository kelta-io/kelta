package io.kelta.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The error page shown to a browser, and the rule for when a browser is asking.
 *
 * <p>The page ({@code templates/error.html}) shows a neutral title and message for the status
 * and a short request id that is also in the log line, never the exception or a stack trace.
 * Its model holds plain strings only (the native image cannot call methods from a template).
 */
final class ErrorPages {

    static final String VIEW = "error";

    private ErrorPages() {
    }

    /**
     * Whether the reply to {@code request} should be the HTML page rather than JSON:
     * <ol>
     *   <li>the response was already marked {@code text/html} — on the {@code /error} path, a
     *       page that failed while it was being rendered. Answering that with Spring Boot's JSON
     *       error map is what failed with "No converter for [class java.util.LinkedHashMap] with
     *       preset Content-Type 'text/html'". (For a handler exception the dispatcher clears the
     *       content type first.)</li>
     *   <li>otherwise the handler, when known: a page handler ({@code @Controller} method that
     *       returns a view or writes the response itself) gets HTML, a {@code @ResponseBody} or
     *       {@code ResponseEntity} handler (the JSON APIs) gets JSON;</li>
     *   <li>otherwise the {@code Accept} header: HTML only when it names {@code text/html} (or
     *       XHTML) ahead of any JSON type. A missing header or a bare {@code *}{@code /*} gets
     *       JSON, as API clients and scripts send.</li>
     * </ol>
     */
    static boolean wantsHtml(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String contentType = response.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith(MediaType.TEXT_HTML_VALUE)) {
            return true;
        }
        if (handler instanceof HandlerMethod method) {
            return isPageHandler(method);
        }
        return acceptsHtmlFirst(request.getHeader("Accept"));
    }

    static boolean isPageHandler(HandlerMethod method) {
        if (AnnotatedElementUtils.hasAnnotation(method.getBeanType(), ResponseBody.class)
                || method.hasMethodAnnotation(ResponseBody.class)) {
            return false;
        }
        return !HttpEntity.class.isAssignableFrom(method.getReturnType().getParameterType());
    }

    static boolean acceptsHtmlFirst(String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        List<MediaType> types;
        try {
            types = new ArrayList<>(MediaType.parseMediaTypes(accept));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
        // Most specific first, then highest quality first (a stable sort keeps specificity order).
        MimeTypeUtils.sortBySpecificity(types);
        types.sort(Comparator.comparingDouble(MediaType::getQualityValue).reversed());
        for (MediaType type : types) {
            if (type.isWildcardType()) {
                continue;
            }
            if (type.isCompatibleWith(MediaType.TEXT_HTML) || type.isCompatibleWith(MediaType.APPLICATION_XHTML_XML)) {
                return true;
            }
            if (type.isCompatibleWith(MediaType.APPLICATION_JSON) || type.getSubtype().endsWith("+json")) {
                return false;
            }
        }
        return false;
    }

    /** The HTTP status an exception thrown by a handler stands for. */
    static HttpStatusCode statusFor(Throwable error) {
        if (error instanceof ErrorResponse errorResponse) {
            return errorResponse.getStatusCode();
        }
        if (error != null) {
            ResponseStatus annotated = AnnotatedElementUtils.findMergedAnnotation(error.getClass(), ResponseStatus.class);
            if (annotated != null) {
                return annotated.code();
            }
        }
        if (error instanceof AuthenticationException) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (error instanceof AccessDeniedException) {
            return HttpStatus.FORBIDDEN;
        }
        if (error instanceof TypeMismatchException) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** A short id that ties the page (or JSON body) to the log line. */
    static String newRequestId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    }

    /** The error page for {@code status}, with the HTTP status set on the view. */
    static ModelAndView page(HttpStatusCode status, String requestId) {
        ModelAndView page = new ModelAndView(VIEW);
        page.setStatus(status);
        page.addObject("status", String.valueOf(status.value()));
        page.addObject("title", title(status));
        page.addObject("message", message(status));
        page.addObject("requestId", requestId);
        return page;
    }

    static String title(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Invalid request";
            case 401 -> "Sign-in required";
            case 403 -> "Access denied";
            case 404 -> "Page not found";
            case 405 -> "Not allowed";
            case 429 -> "Too many requests";
            default -> status.is5xxServerError() ? "Something went wrong" : "Request not completed";
        };
    }

    static String message(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "The request could not be understood. Please go back and try again.";
            case 401 -> "Please sign in to continue.";
            case 403 -> "You do not have access to this page.";
            case 404 -> "The page you were looking for does not exist.";
            case 405 -> "This page cannot be used this way.";
            case 429 -> "Please wait a moment and try again.";
            default -> status.is5xxServerError()
                    ? "The request could not be completed. Please try again in a moment."
                    : "The request could not be completed. Please go back and try again.";
        };
    }
}
