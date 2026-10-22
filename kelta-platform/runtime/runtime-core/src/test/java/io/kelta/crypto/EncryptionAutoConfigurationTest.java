package io.kelta.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code kelta.encryption.key} is declared as {@code ${KELTA_ENCRYPTION_KEY:}}, so an unset key
 * arrives blank, and {@code @ConditionalOnProperty} without {@code havingValue} matches a blank
 * value: the bean method always runs and has to decide itself.
 */
@DisplayName("EncryptionAutoConfiguration")
class EncryptionAutoConfigurationTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EncryptionAutoConfiguration.class));

    @Test
    @DisplayName("registers the service for a configured key")
    void configuredKey() {
        runner.withPropertyValues("kelta.encryption.key=" + KEY)
                .run(context -> assertThat(context.getBeanProvider(EncryptionService.class).getIfAvailable())
                        .isNotNull());
    }

    @Test
    @DisplayName("a blank key leaves the service absent where encryption is optional (kelta-auth)")
    void blankOptional() {
        runner.withPropertyValues("kelta.encryption.key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanProvider(EncryptionService.class).getIfAvailable()).isNull();
                });
    }

    @Test
    @DisplayName("a blank key fails startup, naming the variable, where encryption is required (kelta-worker)")
    void blankRequired() {
        runner.withPropertyValues("kelta.encryption.key=", "kelta.encryption.required=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .hasMessageContaining("KELTA_ENCRYPTION_KEY")
                        .hasMessageContaining("openssl rand -base64 32"));
    }

    @Test
    @DisplayName("no property at all registers nothing (services that never declare a key)")
    void undeclared() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EncryptionService.class);
        });
    }
}
