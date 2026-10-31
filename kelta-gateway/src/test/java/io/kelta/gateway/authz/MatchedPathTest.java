package io.kelta.gateway.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MatchedPath — the path as the service behind the gateway matches it")
class MatchedPathTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/ai/config,          /api/ai/config",
            "/api/ai/%63onfig,        /api/ai/config",
            "/api/%61i/%43onfig,      /api/ai/Config",
            "/api/ai/config/,         /api/ai/config",
            "/api/ai;v=1/config;x=2,  /api/ai/config",
            "//api//ai/config,        /api/ai/config",
            "/,                       /",
            "/api/ai/%zz,             /api/ai/%zz",
    })
    void matchedForm(String raw, String expected) {
        assertThat(MatchedPath.of(raw)).isEqualTo(expected);
    }
}
