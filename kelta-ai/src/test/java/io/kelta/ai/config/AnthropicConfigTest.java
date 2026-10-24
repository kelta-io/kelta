package io.kelta.ai.config;

import com.anthropic.client.AnthropicClient;
import io.kelta.ai.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AnthropicConfig")
class AnthropicConfigTest {

    private final AnthropicConfig config = new AnthropicConfig();

    @Test
    @DisplayName("builds a client from a configured API key")
    void buildsWithConfiguredKey() {
        AnthropicClient client = config.anthropicClient(TestFixtures.aiConfig());

        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    @DisplayName("falls back to the SDK's environment detection when no key is configured, without failing startup")
    void buildsFromEnvironmentWhenKeyBlank() {
        AiConfigProperties base = TestFixtures.aiConfig();
        AiConfigProperties noKey = new AiConfigProperties(
                new AiConfigProperties.AnthropicProperties("", "claude-sonnet-4-20250514", 4096, 0.7),
                base.workerServiceUrl(), base.sseTimeoutMs(), base.rateLimit());

        AnthropicClient client = config.anthropicClient(noKey);

        assertThat(client).isNotNull();
        client.close();
    }
}
