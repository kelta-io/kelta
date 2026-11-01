package io.kelta.gateway.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bearer-token converter for the {@code oauth2ResourceServer} chain that
 * filters out Personal Access Tokens.
 *
 * <p>{@link PatAuthenticationFilter} (a {@code GlobalFilter} ordered
 * before the Spring Security {@code WebFilterChainProxy}) already
 * validates {@code klt_*} tokens, populates the gateway principal,
 * and propagates {@code X-User-Id} downstream. By the time Spring
 * Security's {@code AuthenticationWebFilter} runs, those requests
 * are already authenticated as far as the gateway cares.
 *
 * <p>If we leave the default {@link ServerBearerTokenAuthenticationConverter}
 * in place, it produces a {@code BearerTokenAuthenticationToken} for
 * the {@code klt_*} token — and {@code JwtReactiveAuthenticationManager}
 * either fails parsing it as a JWT (5xx) or, if the decoder swallows
 * the token, leaves the manager with nothing to return and the
 * {@code AuthenticationWebFilter} surfaces "No provider found".
 *
 * <p>Returning {@link Mono#empty()} from the converter for
 * {@code klt_*} tokens makes {@code AuthenticationWebFilter} skip
 * authentication entirely and continue the filter chain, which —
 * combined with {@code anyExchange().permitAll()} — lets the request
 * proceed with the principal that the global PAT filter already set.
 *
 * <p>The same goes for a SCIM client's token on {@code /scim/**}: it is an opaque
 * secret the worker's SCIM filter checks against {@code scim_client}, not a JWT, and
 * the gateway treats {@code /scim} as an unauthenticated path. Parsing it as a JWT
 * would refuse every SCIM request before it is routed.
 */
public final class PatAwareBearerTokenConverter implements ServerAuthenticationConverter {

    static final String PAT_PREFIX = "klt_";
    static final String AUTHORIZATION = "Authorization";
    static final String BEARER_PREFIX = "Bearer ";
    static final String SCIM_PATH_PREFIX = "/scim/";

    private final ServerAuthenticationConverter delegate;

    public PatAwareBearerTokenConverter() {
        this(new ServerBearerTokenAuthenticationConverter());
    }

    PatAwareBearerTokenConverter(ServerAuthenticationConverter delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            if (token.startsWith(PAT_PREFIX)) {
                return Mono.empty();
            }
            if (exchange.getRequest().getPath().value().startsWith(SCIM_PATH_PREFIX)) {
                return Mono.empty();
            }
        }
        return delegate.convert(exchange);
    }
}
