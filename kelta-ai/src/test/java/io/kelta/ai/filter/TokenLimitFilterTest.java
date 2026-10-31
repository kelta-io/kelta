package io.kelta.ai.filter;

import io.kelta.ai.service.TokenTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenLimitFilter")
class TokenLimitFilterTest {

    @Mock
    private TokenTrackingService tokenTrackingService;

    private TokenLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TokenLimitFilter(tokenTrackingService);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/ai/chat",
            "/api/ai/chat/stream",
            "/api/ai/%63hat",
            "/api/ai/ch%61t/stream",
            "/api/ai//chat",
            "/api/ai/chat;x=1",
            "/api//ai/chat"
    })
    @DisplayName("filters every spelling of a chat path")
    void filtersChatPaths(String path) {
        assertThat(filter.shouldNotFilter(request(path))).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/ai/config", "/api/ai/conversations", "/api/ai/%63onfig", "/"})
    @DisplayName("leaves other AI paths alone")
    void skipsOtherPaths(String path) {
        assertThat(filter.shouldNotFilter(request(path))).isTrue();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }
}
