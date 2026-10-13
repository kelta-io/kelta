package io.kelta.auth.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.CredentialsContainer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KeltaUserDetails")
class KeltaUserDetailsTest {

    private static final String HASH = "$2a$10$exampleexampleexampleexampleexampleexampleexample";

    private static KeltaUserDetails user(String userId, String tenantId, String passwordHash) {
        return new KeltaUserDetails(userId, "user@example.test", tenantId, "profile-1",
                "Standard", "Test User", passwordHash, true, false, false);
    }

    @Test
    @DisplayName("is a CredentialsContainer, so ProviderManager erases it after authentication")
    void isCredentialsContainer() {
        assertThat(user("u1", "t1", HASH)).isInstanceOf(CredentialsContainer.class);
    }

    @Test
    @DisplayName("eraseCredentials clears the password hash")
    void eraseCredentialsClearsPasswordHash() {
        KeltaUserDetails user = user("u1", "t1", HASH);

        user.eraseCredentials();

        assertThat(user.getPassword()).isNull();
        assertThat(user.getPasswordHash()).isNull();
    }

    @Test
    @DisplayName("the local-credential flag survives erasure")
    void localCredentialSurvivesErasure() {
        KeltaUserDetails internal = user("u1", "t1", HASH);
        KeltaUserDetails federated = user("u2", "t1", "");
        KeltaUserDetails noHash = user("u3", "t1", null);

        internal.eraseCredentials();

        assertThat(internal.hasLocalCredential()).isTrue();
        assertThat(federated.hasLocalCredential()).isFalse();
        assertThat(noHash.hasLocalCredential()).isFalse();
    }

    @Test
    @DisplayName("equality is tenant + user id, before and after erasure")
    void equalityIsTenantAndUserId() {
        KeltaUserDetails a = user("u1", "t1", HASH);
        KeltaUserDetails sameUser = user("u1", "t1", "other-hash");
        KeltaUserDetails otherTenant = user("u1", "t2", HASH);
        KeltaUserDetails otherUser = user("u2", "t1", HASH);
        int hashBefore = a.hashCode();

        a.eraseCredentials();

        assertThat(a).isEqualTo(sameUser).hasSameHashCodeAs(sameUser);
        assertThat(a.hashCode()).isEqualTo(hashBefore);
        assertThat(a).isNotEqualTo(otherTenant).isNotEqualTo(otherUser);
    }
}
