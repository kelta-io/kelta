package io.kelta.gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

class PatAwareBearerTokenConverterTest {

    @Test
    void returnsEmptyForKltPatToken() {
        PatAwareBearerTokenConverter converter = new PatAwareBearerTokenConverter(
                exchange -> Mono.error(new AssertionError("delegate should not run for klt_ tokens")));

        MockServerWebExchange exchange = exchange("Bearer klt_AbCdEf12345");

        StepVerifier.create(converter.convert(exchange))
                .verifyComplete();
    }

    @Test
    void delegatesForRealJwtToken() {
        Authentication stub = new StubAuth();
        ServerAuthenticationConverter delegate = exchange -> Mono.just(stub);
        PatAwareBearerTokenConverter converter = new PatAwareBearerTokenConverter(delegate);

        MockServerWebExchange exchange = exchange("Bearer eyJhbGciOiJIUzI1NiJ9.x.y");

        StepVerifier.create(converter.convert(exchange))
                .expectNext(stub)
                .verifyComplete();
    }

    @Test
    void returnsEmptyForAScimClientTokenOnAScimPath() {
        PatAwareBearerTokenConverter converter = new PatAwareBearerTokenConverter(
                exchange -> Mono.error(new AssertionError("delegate should not run for a SCIM token")));

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer 3f9c2a7e5b1d4c8a9e0f6b2d7a1c5e3f");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/scim/v2/Users").headers(headers).build());

        StepVerifier.create(converter.convert(exchange))
                .verifyComplete();
    }

    @Test
    void delegatesForANonPatTokenOutsideScim() {
        Authentication stub = new StubAuth();
        PatAwareBearerTokenConverter converter = new PatAwareBearerTokenConverter(exchange -> Mono.just(stub));

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer 3f9c2a7e5b1d4c8a9e0f6b2d7a1c5e3f");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/acme/api/scim-like").headers(headers).build());

        StepVerifier.create(converter.convert(exchange))
                .expectNext(stub)
                .verifyComplete();
    }

    @Test
    void delegatesWhenAuthorizationHeaderAbsent() {
        Authentication stub = new StubAuth();
        ServerAuthenticationConverter delegate = exchange -> Mono.just(stub);
        PatAwareBearerTokenConverter converter = new PatAwareBearerTokenConverter(delegate);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/anything").build());

        StepVerifier.create(converter.convert(exchange))
                .expectNext(stub)
                .verifyComplete();
    }

    @Test
    void delegatesWhenSchemeIsNotBearer() {
        Authentication stub = new StubAuth();
        ServerAuthenticationConverter delegate = exchange -> Mono.just(stub);
        PatAwareBearerTokenConverter converter = new PatAwareBearerTokenConverter(delegate);

        MockServerWebExchange exchange = exchange("Basic dXNlcjpwYXNz");

        StepVerifier.create(converter.convert(exchange))
                .expectNext(stub)
                .verifyComplete();
    }

    private static MockServerWebExchange exchange(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", authorization);
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/anything").headers(headers).build());
    }

    private static final class StubAuth extends AbstractAuthenticationToken {
        StubAuth() { super(List.of()); }
        @Override public Object getCredentials() { return null; }
        @Override public Object getPrincipal() { return "stub"; }
    }
}
