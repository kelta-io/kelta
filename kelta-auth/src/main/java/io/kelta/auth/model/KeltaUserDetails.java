package io.kelta.auth.model;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The platform's authenticated principal.
 * <p>
 * Implements {@link CredentialsContainer} so Spring Security's {@code ProviderManager} erases the
 * password hash once authentication succeeds. The principal outlives the login: it is held in the
 * HTTP session and serialized into the OAuth2 authorization store, and neither needs the hash.
 * Whether the user signed in with a local password is kept separately as
 * {@link #hasLocalCredential()}, so it survives erasure.
 */
public class KeltaUserDetails implements UserDetails, CredentialsContainer {

    /**
     * Pinned so a principal in a stored HTTP session (Java serialization, Redis) still reads
     * after a release that changes this class compatibly. Bump it only for an incompatible
     * change; sessions holding the old form then read as signed out.
     */
    @Serial
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String email;
    private final String tenantId;
    private final String profileId;
    private final String profileName;
    private final String displayName;
    /** Not final: {@link #eraseCredentials()} clears it after authentication. */
    private String passwordHash;
    private final boolean active;
    private final boolean locked;
    private final boolean forceChangePassword;
    private final String userType;
    private final boolean localCredential;

    public KeltaUserDetails(String id, String email, String tenantId, String profileId,
                            String profileName, String displayName, String passwordHash,
                            boolean active, boolean locked, boolean forceChangePassword) {
        this(id, email, tenantId, profileId, profileName, displayName, passwordHash,
                active, locked, forceChangePassword, "INTERNAL");
    }

    public KeltaUserDetails(String id, String email, String tenantId, String profileId,
                            String profileName, String displayName, String passwordHash,
                            boolean active, boolean locked, boolean forceChangePassword,
                            String userType) {
        this(id, email, tenantId, profileId, profileName, displayName, passwordHash,
                active, locked, forceChangePassword, userType, isPresent(passwordHash));
    }

    private KeltaUserDetails(String id, String email, String tenantId, String profileId,
                             String profileName, String displayName, String passwordHash,
                             boolean active, boolean locked, boolean forceChangePassword,
                             String userType, boolean localCredential) {
        this.id = id;
        this.email = email;
        this.tenantId = tenantId;
        this.profileId = profileId;
        this.profileName = profileName;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.active = active;
        this.locked = locked;
        this.forceChangePassword = forceChangePassword;
        this.userType = userType;
        this.localCredential = localCredential;
    }

    /**
     * Rebuilds a principal from its serialized form (see {@link KeltaUserDetailsMixin}). The
     * result never carries a password hash. Rows written before {@code localCredential} was
     * stored have it {@code null} and may still hold {@code storedPasswordHash}; its presence
     * decides {@code localCredential}, and the hash itself is dropped.
     */
    static KeltaUserDetails restore(String id, String email, String tenantId, String profileId,
                                    String profileName, String displayName, String storedPasswordHash,
                                    boolean active, boolean locked, boolean forceChangePassword,
                                    String userType, Boolean localCredential) {
        boolean local = localCredential != null ? localCredential : isPresent(storedPasswordHash);
        return new KeltaUserDetails(id, email, tenantId, profileId, profileName, displayName, null,
                active, locked, forceChangePassword, userType, local);
    }

    private static boolean isPresent(String passwordHash) {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getTenantId() { return tenantId; }
    public String getProfileId() { return profileId; }
    public String getProfileName() { return profileName; }
    public String getDisplayName() { return displayName; }
    /** The stored password hash, or {@code null} once credentials have been erased. */
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return active; }
    public boolean isLocked() { return locked; }
    public boolean isForceChangePassword() { return forceChangePassword; }
    /** INTERNAL or PORTAL; null (pre-userType serialized principals) reads as INTERNAL. */
    public String getUserType() { return userType == null ? "INTERNAL" : userType; }

    /**
     * Whether this user has a local password credential (internal login) rather than a
     * federated or passwordless sign-in. Fixed at construction, so it still answers after
     * {@link #eraseCredentials()}.
     */
    public boolean hasLocalCredential() { return localCredential; }

    /** Clears the password hash. Called by {@code ProviderManager} after authentication. */
    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return !forceChangePassword;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    /**
     * Two instances are the same user when they carry the same user id in the same tenant.
     * <p>
     * Spring Authorization Server finds a user's sessions in its {@code SessionRegistry} by
     * principal: when it puts {@code sid} into an ID token (from the principal read back from
     * the JDBC authorization store, a new instance) and when the end-session endpoint checks
     * that {@code sid} (from the principal in the HTTP session). With identity equality the
     * first lookup never matched, so ID tokens carried no {@code sid} and sign-out was rejected
     * with {@code invalid_token}.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof KeltaUserDetails that)) {
            return false;
        }
        return Objects.equals(id, that.id) && Objects.equals(tenantId, that.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tenantId);
    }
}
