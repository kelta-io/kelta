package io.kelta.auth.session;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SingleIndexResolver;

/**
 * Indexes each session under its signed-in user's {@link SessionPrincipalKeys key}, so the
 * session store can list one user's sessions ({@link TenantScopedSessionRegistry}).
 *
 * <p>Replaces Spring Session's default resolver, which indexes by login name and reads it with
 * a SpEL expression; plain code needs no reflection metadata in the native image.
 */
public final class SessionPrincipalIndexResolver extends SingleIndexResolver<Session> {

    public SessionPrincipalIndexResolver() {
        super(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
    }

    @Override
    public String resolveIndexValueFor(Session session) {
        return SessionPrincipalKeys.forSession(session);
    }
}
