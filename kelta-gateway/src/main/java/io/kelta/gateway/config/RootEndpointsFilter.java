package io.kelta.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Answers {@code GET /robots.txt} and {@code GET /} on API hosts directly.
 *
 * <p>Crawlers and uptime probes hit both on every API host. Without this, each request fell
 * through to WebFlux's static-resource handler and logged a stack-bearing
 * {@code 404 NOT_FOUND "No static resource"} WARN for expected traffic.
 *
 * <p>Both paths are matched <em>exactly</em> and short-circuit (order -320) before custom-domain and
 * tenant-slug resolution (-310/-300) and before auth, so neither needs a tenant or a token.
 * They are deliberately not added to {@code kelta.gateway.security.public-paths}:
 * {@code PublicPathMatcher} matches by prefix, and a bare {@code /} there would make every
 * route public. The root body names the service and its docs only — no version, build,
 * commit or host details.
 */
@Component
public class RootEndpointsFilter implements WebFilter, Ordered {

    static final String ROBOTS_PATH = "/robots.txt";
    static final String ROOT_PATH = "/";
    static final String ROBOTS_BODY = "User-agent: *\nDisallow: /";

    private final byte[] rootBody;

    public RootEndpointsFilter(
            @Value("${kelta.gateway.root.service-name:kelta-api}") String serviceName,
            @Value("${kelta.gateway.root.docs-url:https://kelta.io/docs}") String docsUrl) {
        this.rootBody = ("{\"service\":\"" + jsonEscape(serviceName)
                + "\",\"docs\":\"" + jsonEscape(docsUrl) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return -320;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (!HttpMethod.GET.equals(method) && !HttpMethod.HEAD.equals(method)) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        if (ROBOTS_PATH.equals(path)) {
            return write(exchange.getResponse(), MediaType.TEXT_PLAIN,
                    ROBOTS_BODY.getBytes(StandardCharsets.UTF_8));
        }
        if (ROOT_PATH.equals(path)) {
            return write(exchange.getResponse(), MediaType.APPLICATION_JSON, rootBody);
        }
        return chain.filter(exchange);
    }

    private static Mono<Void> write(ServerHttpResponse response, MediaType contentType, byte[] body) {
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(contentType);
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
