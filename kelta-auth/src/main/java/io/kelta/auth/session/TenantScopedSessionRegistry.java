package io.kelta.auth.session;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * The OIDC {@code SessionRegistry}: a view of the shared session store, so every replica sees
 * every session and a session survives a restart.
 *
 * <p>Spring Authorization Server looks a user's sessions up by principal when it writes the ID
 * token's {@code sid} (the principal read back from the JDBC authorization store) and when the
 * end-session endpoint checks it (the principal in the HTTP session). Both are a
 * {@code KeltaUserDetails}, looked up here under the same tenant-and-user key the sessions are
 * indexed with ({@link SessionPrincipalIndexResolver}), so two users who share an email address
 * in different tenants never see each other's sessions.
 *
 * <p>Registering, forgetting and touching a session are no-ops: the store tracks sessions
 * itself, and each request through the session filter updates the session's last access time,
 * which is what Spring Authorization Server orders a user's sessions by.
 */
public class TenantScopedSessionRegistry<S extends Session> extends SpringSessionBackedSessionRegistry<S> {

    public TenantScopedSessionRegistry(FindByIndexNameSessionRepository<S> sessionRepository) {
        super(sessionRepository);
    }

    @Override
    protected String name(Object principal) {
        return SessionPrincipalKeys.of(principal);
    }
}
