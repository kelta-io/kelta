package io.kelta.worker.config;

import io.kelta.crypto.EncryptionAutoConfiguration;
import io.kelta.runtime.credential.CredentialTypeRegistry;
import io.kelta.worker.listener.CredentialEncryptionHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The worker cannot run without {@code KELTA_ENCRYPTION_KEY}: credentials, tenant SMTP settings
 * and support mailboxes all encrypt with it. Its {@code application.yml} marks the key required,
 * so a blank key stops startup with a message naming the variable, instead of a
 * {@code NoSuchBeanDefinitionException} for {@code EncryptionService} from whichever of those
 * beans happened to be created first.
 */
@DisplayName("Worker encryption key")
class WorkerEncryptionKeyWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(EncryptionAutoConfiguration.class))
            .withUserConfiguration(CredentialEncryptionHook.class)
            .withBean(CredentialTypeRegistry.class, () -> mock(CredentialTypeRegistry.class))
            .withBean(ObjectMapper.class, () -> mock(ObjectMapper.class));

    @Test
    @DisplayName("a blank key fails startup naming KELTA_ENCRYPTION_KEY")
    void blankKeyFailsClearly() {
        runner.withPropertyValues("kelta.encryption.key=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .hasMessageContaining("KELTA_ENCRYPTION_KEY"));
    }

    @Test
    @DisplayName("a configured key starts the encryption-dependent beans")
    void configuredKey() {
        runner.withPropertyValues("kelta.encryption.key=" + Base64.getEncoder().encodeToString(new byte[32]))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CredentialEncryptionHook.class);
                });
    }
}
