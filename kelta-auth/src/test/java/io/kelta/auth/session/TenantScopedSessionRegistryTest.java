package io.kelta.auth.session;

import io.kelta.auth.model.KeltaUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.DelegatingIndexResolver;
import org.springframework.session.MapSession;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantScopedSessionRegistry — a user's sessions, by tenant and user id")
class TenantScopedSessionRegistryTest {

    private static final String EMAIL = "same@example.test";

    private final InMemoryIndexedSessionRepository sessions = new InMemoryIndexedSessionRepository(
            new DelegatingIndexResolver<>(new SessionPrincipalIndexResolver()),
            new SessionAttributeSerializer(getClass().getClassLoader()), Duration.ofMinutes(30));
    private final TenantScopedSessionRegistry<MapSession> registry = new TenantScopedSessionRegistry<>(sessions);

    @Test
    @DisplayName("the same email address in two tenants is two users with separate sessions")
    void sameEmailInAnotherTenantIsAnotherUser() {
        KeltaUserDetails tenantA = user("tenant-a-id", "user-a-id");
        KeltaUserDetails tenantB = user("tenant-b-id", "user-b-id");
        String sessionA = signedIn(tenantA);
        String sessionB = signedIn(tenantB);

        assertThat(registry.getAllSessions(tenantA, false)).extracting(SessionInformation::getSessionId)
                .containsExactly(sessionA);
        assertThat(registry.getAllSessions(user("tenant-b-id", "user-b-id"), false))
                .extracting(SessionInformation::getSessionId).containsExactly(sessionB);
    }

    @Test
    @DisplayName("a session is found by id while it lasts, and not after it ends")
    void sessionInformationFollowsTheStore() {
        String id = signedIn(user("tenant-a-id", "user-a-id"));
        assertThat(registry.getSessionInformation(id)).isNotNull();

        sessions.deleteById(id);

        assertThat(registry.getSessionInformation(id)).isNull();
        assertThat(registry.getAllSessions(user("tenant-a-id", "user-a-id"), true)).isEmpty();
    }

    @Test
    @DisplayName("the index key is tenant and user id — no email address")
    void indexKeyHoldsIdsOnly() {
        assertThat(SessionPrincipalKeys.of(user("tenant-a-id", "user-a-id"))).isEqualTo("tenant-a-id:user-a-id");
    }

    @Test
    @DisplayName("a session nobody has signed in to is not indexed")
    void anonymousSessionIsNotIndexed() {
        MapSession session = sessions.createSession();
        sessions.save(session);

        assertThat(new SessionPrincipalIndexResolver().resolveIndexValueFor(session)).isNull();
    }

    private String signedIn(KeltaUserDetails user) {
        MapSession session = sessions.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())));
        sessions.save(session);
        return session.getId();
    }

    private static KeltaUserDetails user(String tenantId, String userId) {
        return new KeltaUserDetails(userId, EMAIL, tenantId, "profile-1", "Standard", "User", null,
                true, false, false);
    }
}
