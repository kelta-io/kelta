package io.kelta.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration for {@link EncryptionService}.
 *
 * <p>The key must be a Base64-encoded 32-byte (256-bit) AES key. Services that use encryption
 * declare it in {@code application.yml} as {@code ${KELTA_ENCRYPTION_KEY:}}, so an unset key
 * arrives <em>blank</em> — and {@code @ConditionalOnProperty} without {@code havingValue} matches
 * a blank value. The gate therefore only separates services that declare the key from those that
 * don't (kelta-ai, kelta-mcp: no bean); for the declaring services this method always runs and
 * decides from the value at startup, which is also what keeps the native images (kelta-auth)
 * working, since they freeze conditions at build time.
 *
 * <ul>
 *   <li>Configured key: the service is registered.</li>
 *   <li>Blank key, {@code kelta.encryption.required=true} (kelta-worker): startup fails, naming
 *       {@code KELTA_ENCRYPTION_KEY}. The worker's credentials, tenant SMTP settings and support
 *       mailboxes cannot work without it, and without this check the failure surfaced as a
 *       {@code NoSuchBeanDefinitionException} from whichever of those beans was created first.</li>
 *   <li>Blank key, not required (kelta-auth): no service — federation and TOTP-secret encryption
 *       resolve it with {@code ObjectProvider.getIfAvailable()} and stay off.</li>
 * </ul>
 *
 * <p>Generate a key:
 * <pre>{@code
 * openssl rand -base64 32
 * }</pre>
 */
@Configuration
public class EncryptionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EncryptionAutoConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "kelta.encryption.key")
    public EncryptionService encryptionService(Environment env) {
        String key = env.getProperty("kelta.encryption.key");
        if (key == null || key.isBlank()) {
            if (env.getProperty("kelta.encryption.required", Boolean.class, false)) {
                throw new IllegalStateException("KELTA_ENCRYPTION_KEY (kelta.encryption.key) is required "
                        + "by this service and is not set. Generate one with `openssl rand -base64 32` "
                        + "(`make gen-keys` writes it to .env).");
            }
            log.info("EncryptionService not configured — kelta.encryption.key is empty");
            return null;
        }
        log.info("EncryptionService initialized with AES-256-GCM");
        return new EncryptionService(key);
    }
}
