package io.kelta.worker.service.credential;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.credential.ResolvedCredential;
import io.kelta.worker.repository.CredentialOAuthTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CredentialResolverPortAdapter")
class CredentialResolverPortAdapterTest {

    private final CredentialResolver resolver = mock(CredentialResolver.class);
    private final CredentialResolverPortAdapter adapter = new CredentialResolverPortAdapter(resolver,
            mock(CredentialOAuthTokenRepository.class), mock(EncryptionService.class));

    private static ResolvedCredential credential() {
        return new ResolvedCredential("cred-1", "stripe", "stripe", Map.of(), Map.of(), Instant.now());
    }

    @Test
    @DisplayName("the actor form records the actor as the audit row's user, with the purpose")
    void actorIsAudited() {
        ResolutionContext expected = ResolutionContext.forUser("svc-1", "MODULE:stub");
        when(resolver.resolve("tenant-1", "stripe", expected)).thenReturn(credential());

        assertThat(adapter.resolve("tenant-1", "stripe", "MODULE:stub", "svc-1").name()).isEqualTo("stripe");
        verify(resolver).resolve(eq("tenant-1"), eq("stripe"), eq(expected));
    }

    @Test
    @DisplayName("the three-argument form keeps recording no user")
    void noActor() {
        ResolutionContext expected = ResolutionContext.forUser(null, "CALL_API:crm");
        when(resolver.resolve("tenant-1", "crm", expected)).thenReturn(credential());

        adapter.resolve("tenant-1", "crm", "CALL_API:crm");
        verify(resolver).resolve(eq("tenant-1"), eq("crm"), eq(expected));
    }
}
