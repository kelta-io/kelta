package io.kelta.auth.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The servlet container's error page ({@code /error}): errors that never reached a controller
 * (a filter failed, a page failed while rendering, {@code sendError}, an unknown path).
 * Replaces Spring Boot's {@code BasicErrorController} and its whitelabel page.
 *
 * <ul>
 *   <li>A browser — the failed response was already {@code text/html}, or {@code Accept} puts
 *       {@code text/html} first — gets the HTML error page ({@link ErrorPages}).</li>
 *   <li>Everything else gets Spring Boot's usual JSON body ({@code timestamp}, {@code status},
 *       {@code error}, {@code path}) plus {@code requestId}, written as {@code application/json}
 *       whatever content type the failed response had set.</li>
 * </ul>
 * Neither shows the exception, its message or a stack trace; those are logged with the request id.
 */
@Controller
@RequestMapping("${spring.web.error.path:/error}")
public class AuthErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(AuthErrorController.class);

    private final ErrorAttributes errorAttributes;
    private final ObjectMapper objectMapper;

    public AuthErrorController(ErrorAttributes errorAttributes, ObjectMapper objectMapper) {
        this.errorAttributes = errorAttributes;
        this.objectMapper = objectMapper;
    }

    @RequestMapping
    public ModelAndView error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpStatusCode status = status(request);
        Throwable error = errorAttributes.getError(new ServletWebRequest(request));
        String requestId = ErrorPages.newRequestId();
        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String failedPath = path instanceof String value ? value : request.getRequestURI();
        if (status.is5xxServerError()) {
            log.error("Unexpected error [requestId={}, status={}] {}: {}", requestId, status.value(), failedPath,
                    error == null ? "(no exception)" : error.toString(), error);
        } else if (log.isDebugEnabled()) {
            log.debug("Request failed [requestId={}, status={}] {}", requestId, status.value(), failedPath);
        }

        if (ErrorPages.wantsHtml(request, response, null)) {
            return ErrorPages.page(status, requestId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        HttpStatus known = HttpStatus.resolve(status.value());
        body.put("error", known == null ? "Error" : known.getReasonPhrase());
        body.put("path", failedPath);
        body.put("requestId", requestId);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
        return null;
    }

    private static HttpStatusCode status(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value && value >= 400 && value < 600) {
            return HttpStatusCode.valueOf(value);
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
