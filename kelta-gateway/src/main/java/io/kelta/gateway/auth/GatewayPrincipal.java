package io.kelta.gateway.auth;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an authenticated user in the gateway.
 * Contains user information extracted from JWT token claims.
 *
 * <p>This class is immutable. Use the {@code with*()} copy methods to create
 * enriched copies as the filter chain progressively resolves identity fields.
 */
public final class GatewayPrincipal {

    private final String username;
    private final List<String> groups;
    private final Map<String, Object> claims;
    private final String profileId;
    private final String profileName;
    private final String tenantId;
    private final String connectedAppId;
    private final String appScopes;
    private final String geoCountry;
    private final String userId;

    /**
     * Creates a new GatewayPrincipal with all fields.
     *
     * @param username       the username of the authenticated user
     * @param groups         the OIDC groups extracted from the JWT token
     * @param claims         all JWT claims for the user
     * @param profileId      the resolved profile ID (may be null)
     * @param profileName    the resolved profile name (may be null)
     * @param tenantId       the resolved tenant ID (may be null)
     * @param connectedAppId the connected app ID for machine tokens (may be null)
     * @param appScopes      the app scopes for connected app tokens (may be null)
     * @param geoCountry     ISO-3166 alpha-2 country of the request origin (may be null)
     */
    public GatewayPrincipal(String username, List<String> groups, Map<String, Object> claims,
                            String profileId, String profileName, String tenantId,
                            String connectedAppId, String appScopes, String geoCountry) {
        this(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes,
                geoCountry, null);
    }

    /**
     * Creates a new GatewayPrincipal with all fields, including the resolved platform user id.
     *
     * @param userId the caller's {@code platform_user.id} UUID, resolved by the gateway — never
     *               taken from a token claim (may be null)
     */
    public GatewayPrincipal(String username, List<String> groups, Map<String, Object> claims,
                            String profileId, String profileName, String tenantId,
                            String connectedAppId, String appScopes, String geoCountry,
                            String userId) {
        this.username = Objects.requireNonNull(username, "username cannot be null");
        this.groups = groups != null ? List.copyOf(groups) : Collections.emptyList();
        this.claims = claims != null ? Map.copyOf(claims) : Collections.emptyMap();
        this.profileId = profileId;
        this.profileName = profileName;
        this.tenantId = tenantId;
        this.connectedAppId = connectedAppId;
        this.appScopes = appScopes;
        this.geoCountry = geoCountry;
        this.userId = userId;
    }

    /**
     * Creates a new GatewayPrincipal without a geo country (request origin not yet resolved).
     */
    public GatewayPrincipal(String username, List<String> groups, Map<String, Object> claims,
                            String profileId, String profileName, String tenantId,
                            String connectedAppId, String appScopes) {
        this(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes, null);
    }

    /**
     * Creates a new GatewayPrincipal with only the core authentication fields.
     * All identity fields (profileId, profileName, tenantId, connectedAppId, appScopes) are null.
     *
     * @param username the username of the authenticated user
     * @param groups   the OIDC groups extracted from the JWT token
     * @param claims   all JWT claims for the user
     */
    public GatewayPrincipal(String username, List<String> groups, Map<String, Object> claims) {
        this(username, groups, claims, null, null, null, null, null);
    }

    /**
     * Gets the username of the authenticated user.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Gets the OIDC groups extracted from the JWT token.
     *
     * @return an immutable list of groups
     */
    public List<String> getGroups() {
        return groups;
    }

    /**
     * Gets all JWT claims for the user.
     *
     * @return an immutable map of claims
     */
    public Map<String, Object> getClaims() {
        return claims;
    }

    /**
     * Checks if the user belongs to a specific OIDC group.
     *
     * @param group the group to check
     * @return true if the user belongs to the group, false otherwise
     */
    public boolean hasGroup(String group) {
        return groups.contains(group);
    }

    public String getProfileId() {
        return profileId;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getConnectedAppId() {
        return connectedAppId;
    }

    public String getAppScopes() {
        return appScopes;
    }

    /**
     * Gets the ISO-3166 alpha-2 country of the request origin, resolved by
     * {@code GeoEnrichmentFilter}. Null when geolocation is unavailable.
     */
    public String getGeoCountry() {
        return geoCountry;
    }

    /**
     * Gets the caller's {@code platform_user.id} UUID, as resolved by the gateway (the PAT's
     * owner, or the worker identity lookup). Null when it has not been resolved.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Returns a copy of this principal with the given resolved platform user id.
     */
    public GatewayPrincipal withUserId(String userId) {
        return new GatewayPrincipal(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes, geoCountry, userId);
    }

    /**
     * Returns a copy of this principal with one claim added or replaced. Only for claims the
     * gateway itself attests (e.g. a PAT owner's {@code user_type}).
     */
    public GatewayPrincipal withClaim(String name, Object value) {
        Map<String, Object> updated = new java.util.HashMap<>(claims);
        updated.put(name, value);
        return new GatewayPrincipal(username, groups, updated, profileId, profileName, tenantId, connectedAppId, appScopes, geoCountry, userId);
    }

    /**
     * Returns a copy of this principal with the given profile ID.
     */
    public GatewayPrincipal withProfileId(String profileId) {
        return new GatewayPrincipal(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes, geoCountry, userId);
    }

    /**
     * Returns a copy of this principal with the given profile name.
     */
    public GatewayPrincipal withProfileName(String profileName) {
        return new GatewayPrincipal(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes, geoCountry, userId);
    }

    /**
     * Returns a copy of this principal with the given tenant ID.
     */
    public GatewayPrincipal withTenantId(String tenantId) {
        return new GatewayPrincipal(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes, geoCountry, userId);
    }

    /**
     * Returns a copy of this principal with the given connected app ID.
     */
    public GatewayPrincipal withConnectedAppId(String connectedAppId) {
        return new GatewayPrincipal(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes, geoCountry, userId);
    }

    /**
     * Returns a copy of this principal with the given app scopes.
     */
    public GatewayPrincipal withAppScopes(String appScopes) {
        return new GatewayPrincipal(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes, geoCountry, userId);
    }

    /**
     * Returns a copy of this principal with the given request-origin country.
     */
    public GatewayPrincipal withGeoCountry(String geoCountry) {
        return new GatewayPrincipal(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes, geoCountry, userId);
    }

    /**
     * Returns true if this principal represents a connected app (machine identity).
     */
    public boolean isConnectedApp() {
        return connectedAppId != null && !connectedAppId.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GatewayPrincipal that = (GatewayPrincipal) o;
        return Objects.equals(username, that.username) &&
               Objects.equals(groups, that.groups) &&
               Objects.equals(claims, that.claims) &&
               Objects.equals(profileId, that.profileId) &&
               Objects.equals(profileName, that.profileName) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(connectedAppId, that.connectedAppId) &&
               Objects.equals(appScopes, that.appScopes) &&
               Objects.equals(geoCountry, that.geoCountry) &&
               Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, groups, claims, profileId, profileName, tenantId, connectedAppId, appScopes, geoCountry, userId);
    }

    @Override
    public String toString() {
        return "GatewayPrincipal{" +
               "username='" + username + '\'' +
               ", groups=" + groups +
               ", profileId='" + profileId + '\'' +
               ", profileName='" + profileName + '\'' +
               ", tenantId='" + tenantId + '\'' +
               ", claims=" + claims.keySet() +
               '}';
    }
}
