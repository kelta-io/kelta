package io.kelta.auth.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Jackson 2 mixin for {@link KeltaUserDetails} in the OAuth2 authorization store.
 * <p>
 * {@code passwordHash} is never written ({@code allowSetters} keeps it readable): rows written
 * before credentials were erased from the authenticated principal may still carry it, and
 * {@link KeltaUserDetails#restore} reads it only to decide {@code localCredential} on such rows,
 * then drops it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY)
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(value = "passwordHash", allowSetters = true, ignoreUnknown = true)
public abstract class KeltaUserDetailsMixin {

    @JsonCreator
    static KeltaUserDetails restore(
            @JsonProperty("id") String id,
            @JsonProperty("email") String email,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("profileId") String profileId,
            @JsonProperty("profileName") String profileName,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("passwordHash") String storedPasswordHash,
            @JsonProperty("active") boolean active,
            @JsonProperty("locked") boolean locked,
            @JsonProperty("forceChangePassword") boolean forceChangePassword,
            @JsonProperty("userType") String userType,
            @JsonProperty("localCredential") Boolean localCredential) {
        throw new UnsupportedOperationException("mixin");
    }
}
