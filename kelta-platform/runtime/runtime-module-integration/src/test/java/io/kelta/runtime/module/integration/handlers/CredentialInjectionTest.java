package io.kelta.runtime.module.integration.handlers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CredentialInjection")
class CredentialInjectionTest {

    @Test
    @DisplayName("Masks only the injected query parameter and keeps the rest of the URL")
    void masksInjectedQueryParam() {
        CredentialInjection injection = new CredentialInjection();
        injection.queryParam("api_key", "k/ey+1");

        assertEquals("https://api.example.com/v1/feed?page=2&api_key=***#top",
            injection.redactUrl("https://api.example.com/v1/feed?page=2&api_key=k%2Fey%2B1#top"));
    }

    @Test
    @DisplayName("Masks a short injected query value by name even though free text keeps it")
    void masksShortQueryValueByName() {
        CredentialInjection injection = new CredentialInjection();
        injection.queryParam("k", "ab");

        assertEquals("https://h/p?k=***", injection.redactUrl("https://h/p?k=ab"));
        assertEquals("about", injection.redact("about"));
    }

    @Test
    @DisplayName("Masks a header value and its secret parts, longest first")
    void masksHeaderAndParts() {
        CredentialInjection injection = new CredentialInjection();
        injection.header("Authorization", "Bearer tok_12345", "tok_12345");

        assertEquals("sent *** then ***", injection.redact("sent Bearer tok_12345 then tok_12345"));
        assertEquals("Bearer tok_12345", injection.headers().get("Authorization"));
    }

    @Test
    @DisplayName("With nothing injected, text and URLs are returned unchanged")
    void noInjectionNoChange() {
        CredentialInjection injection = new CredentialInjection();

        assertEquals("https://h/p?api_key=x", injection.redactUrl("https://h/p?api_key=x"));
        assertNull(injection.redact(null));
    }
}
