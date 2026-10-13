package io.kelta.auth.model;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class KeltaUserDetails implements UserDetails {

    private final String id;
    private final String email;
    private final String tenantId;
    private final String profileId;
    private final String profileName;
    private final String displayName;
    private final String passwordHash;
    private final boolean active;
    private final boolean locked;
    private final boolean forceChangePassword;
    private final String userType;

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
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getTenantId() { return tenantId; }
    public String getProfileId() { return profileId; }
    public String getProfileName() { return profileName; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return active; }
    public boolean isLocked() { return locked; }
    public boolean isForceChangePassword() { return forceChangePassword; }
    /** INTERNAL or PORTAL; null (pre-userType serialized principals) reads as INTERNAL. */
    public String getUserType() { return userType == null ? "INTERNAL" : userType; }

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
