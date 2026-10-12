package io.kelta.runtime.model.system;

import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;

import static io.kelta.runtime.model.system.SystemCollectionBuilders.readOnlySystemBuilder;
import static io.kelta.runtime.model.system.SystemCollectionBuilders.systemBuilder;

/**
 * Integration system collections: connected apps and their tokens, OIDC/SAML providers,
 * credentials and OAuth tokens, API specs and operations.
 *
 * <p>Part of the system-collection registry: {@link SystemCollectionDefinitions#all()} is the
 * single entry point and fixes the order. A new definition in this section goes here, plus one
 * {@code definitions.add(...)} line in {@code all()}.
 */
public final class IntegrationSystemCollections {

    private IntegrationSystemCollections() {
        // Utility class
    }

    public static CollectionDefinition connectedApps() {
        return systemBuilder("connected-apps", "Connected Apps", "connected_app")
            .displayFieldName("name")
            .addImmutableField("clientId")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("clientId", 100)
                .withColumnName("client_id").withUnique(true)
                .withDescription("OAuth client identifier."))
            .addField(FieldDefinition.requiredString("clientSecretHash", 200)
                .withColumnName("client_secret_hash").withImmutable(true).withServerOnly(true)
                .withDescription("Hash of the client secret; server-only, never returned."))
            .addField(FieldDefinition.json("redirectUris").withColumnName("redirect_uris")
                .withDescription("JSON array of permitted OAuth redirect URIs."))
            .addField(FieldDefinition.json("scopes")
                .withDescription("Space-separated OAuth scopes."))
            .addField(FieldDefinition.json("ipRestrictions").withColumnName("ip_restrictions")
                .withDescription("JSON array of CIDR ranges the app may call from."))
            .addField(FieldDefinition.integer("rateLimitPerHour")
                .withColumnName("rate_limit_per_hour").withDefault(10000)
                .withDescription("Maximum API calls this app may make per hour."))
            .addField(FieldDefinition.bool("active")
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.datetime("lastUsedAt").withColumnName("last_used_at")
                .withDescription("When the app last called the API."))
            .addField(FieldDefinition.json("grantTypes").withColumnName("grant_types")
                .withDescription("JSON array of OAuth grant types the app may use."))
            .addField(FieldDefinition.bool("requirePkce").withColumnName("require_pkce")
                .withDescription("Whether the authorization-code flow must use PKCE."))
            .addField(FieldDefinition.bool("consentRequired").withColumnName("consent_required")
                .withDescription("Whether the user must consent before tokens are issued."))
            .build();
    }

    public static CollectionDefinition credentials() {
        return systemBuilder("credentials", "Credentials", "credential")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("displayName", 200).withColumnName("display_name")
                .withDescription("Label shown in the UI instead of the raw name."))
            .addField(FieldDefinition.string("description", 500)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("type", 50)
                .withDescription("Credential type, which decides the shape of the encrypted material."))
            .addField(FieldDefinition.string("providerTemplate", 100)
                .withColumnName("provider_template")
                .withDescription("Provider preset that shapes the credential's fields."))
            // dataEnc holds AES-256-GCM ciphertext. The CredentialEncryptionHook
            // populates it from plaintext input fields. It is server-only: the
            // query engine never returns it and drops a caller-supplied value, so
            // the hook path is the only way to set secret material, and the
            // credential services read the column directly to decrypt it.
            .addField(FieldDefinition.text("dataEnc")
                .withColumnName("data_enc").withImmutable(true).withServerOnly(true)
                .withDescription("Encrypted credential material; server-only, never returned."))
            .addField(FieldDefinition.json("metadata")
                .withDescription("Non-secret JSON metadata about the credential."))
            .addField(FieldDefinition.datetime("lastTestAt").withColumnName("last_test_at")
                .withDescription("When the credential was last tested."))
            .addField(FieldDefinition.string("lastTestStatus", 20)
                .withColumnName("last_test_status")
                .withDescription("Outcome of the last credential test."))
            .addField(FieldDefinition.text("lastTestError").withColumnName("last_test_error")
                .withDescription("Error returned by the last failed credential test."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition credentialOauthTokens() {
        return readOnlySystemBuilder("credential-oauth-tokens", "Credential OAuth Tokens",
                "credential_oauth_token")
            .addField(FieldDefinition.lookup("credentialId", "credentials", "Credential")
                .withColumnName("credential_id")
                .withDescription("Credential these tokens belong to."))
            .addField(FieldDefinition.text("accessTokenEnc").withColumnName("access_token_enc")
                .withServerOnly(true)
                .withDescription("Encrypted OAuth access token; server-only, never returned."))
            .addField(FieldDefinition.text("refreshTokenEnc").withColumnName("refresh_token_enc")
                .withServerOnly(true)
                .withDescription("Encrypted OAuth refresh token; server-only, never returned."))
            .addField(FieldDefinition.string("tokenType", 40).withColumnName("token_type")
                .withDescription("OAuth token type, normally Bearer."))
            .addField(FieldDefinition.datetime("expiresAt").withColumnName("expires_at")
                .withDescription("When this record stops being valid."))
            .addField(FieldDefinition.datetime("refreshedAt").withColumnName("refreshed_at")
                .withDescription("When the access token was last refreshed."))
            .addField(FieldDefinition.integer("refreshFailureCount")
                .withColumnName("refresh_failure_count")
                .withDescription("Consecutive refresh failures; resets on success."))
            .addField(FieldDefinition.text("lastRefreshError").withColumnName("last_refresh_error")
                .withDescription("Error returned by the last failed refresh."))
            .addField(FieldDefinition.text("scope")
                .withDescription("OAuth scopes the stored token was granted."))
            .build();
    }

    public static CollectionDefinition apiSpecs() {
        return systemBuilder("api-specs", "API Specs", "api_spec")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 200)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.string("description", 1000)
                .withDescription("Free-text description of what this record is for."))
            .addField(FieldDefinition.requiredString("specVersion", 20)
                .withColumnName("spec_version")
                .withDescription("OpenAPI version of the imported document."))
            .addField(FieldDefinition.string("apiTitle", 500).withColumnName("api_title")
                .withDescription("Title declared by the imported API document."))
            .addField(FieldDefinition.string("apiVersion", 50).withColumnName("api_version")
                .withDescription("Version declared by the imported API document."))
            .addField(FieldDefinition.string("baseUrl", 1000).withColumnName("base_url")
                .withDescription("Base URL callouts against this spec are sent to."))
            .addField(FieldDefinition.json("servers")
                .withDescription("JSON server list declared by the document."))
            .addField(FieldDefinition.json("securitySchemes").withColumnName("security_schemes")
                .withDescription("JSON security schemes declared by the document."))
            .addField(FieldDefinition.requiredString("sourceType", 20)
                .withColumnName("source_type")
                .withDescription("How the specification was supplied: uploaded or fetched by URL."))
            .addField(FieldDefinition.string("sourceUrl", 2000).withColumnName("source_url")
                .withDescription("URL the specification was fetched from."))
            // raw_spec / parsed_spec are exposed but immutable through the
            // dynamic router — imports go through ApiSpecController which runs
            // the parser. Keeping them here lets the system-collection viewer
            // render the spec's metadata without bouncing to a second endpoint.
            .addField(FieldDefinition.requiredText("rawSpec")
                .withColumnName("raw_spec").withImmutable(true)
                .withDescription("Specification document as imported."))
            .addField(FieldDefinition.requiredString("rawFormat", 10)
                .withColumnName("raw_format").withImmutable(true)
                .withDescription("Format of the raw document (JSON or YAML)."))
            .addField(FieldDefinition.requiredJson("parsedSpec")
                .withColumnName("parsed_spec").withImmutable(true)
                .withDescription("Normalized JSON form of the specification."))
            .addField(FieldDefinition.requiredString("specHash", 64)
                .withColumnName("spec_hash").withImmutable(true)
                .withDescription("Digest of the raw document, used to detect changes on re-import."))
            .addField(FieldDefinition.integer("revision").withDefault(1)
                .withDescription("Import revision number, incremented on every re-import."))
            .addField(FieldDefinition.bool("isActive").withColumnName("is_active")
                .withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.datetime("lastImportedAt")
                .withColumnName("last_imported_at")
                .withDescription("When the specification was last imported."))
            .build();
    }

    public static CollectionDefinition apiOperations() {
        return readOnlySystemBuilder("api-operations", "API Operations", "api_operation")
            .addField(FieldDefinition.lookup("specId", "api-specs", "Spec")
                .withColumnName("spec_id")
                .withDescription("API specification this operation belongs to."))
            .addField(FieldDefinition.string("operationId", 200).withColumnName("operation_id")
                .withDescription("operationId declared by the specification."))
            .addField(FieldDefinition.requiredString("syntheticOpId", 200)
                .withColumnName("synthetic_op_id")
                .withDescription("Generated operation id used when the document declares none."))
            .addField(FieldDefinition.requiredString("httpMethod", 10)
                .withColumnName("http_method")
                .withDescription("HTTP method of the operation."))
            .addField(FieldDefinition.requiredString("pathTemplate", 1000)
                .withColumnName("path_template")
                .withDescription("Path template of the operation, with brace parameters."))
            .addField(FieldDefinition.string("summary", 500)
                .withDescription("Summary declared for the operation by the specification."))
            .addField(FieldDefinition.text("description")
                .withDescription("Description declared for the operation by the specification."))
            .addField(FieldDefinition.json("tags")
                .withDescription("JSON tags the operation is grouped under."))
            .addField(FieldDefinition.json("parametersSchema").withColumnName("parameters_schema")
                .withDescription("JSON schema of the operation's parameters."))
            .addField(FieldDefinition.json("requestBodySchema")
                .withColumnName("request_body_schema")
                .withDescription("JSON schema of the operation's request body."))
            .addField(FieldDefinition.json("responseSchemas").withColumnName("response_schemas")
                .withDescription("JSON schemas of the operation's responses by status code."))
            .addField(FieldDefinition.json("securityRequired").withColumnName("security_required")
                .withDescription("Whether the operation requires authentication."))
            .addField(FieldDefinition.bool("deprecated")
                .withDescription("Whether the specification marks the operation deprecated."))
            .addField(FieldDefinition.text("searchText").withColumnName("search_text")
                .withDescription("Denormalized text used to search operations."))
            .build();
    }

    public static CollectionDefinition oidcProviders() {
        return systemBuilder("oidc-providers", "OIDC Providers", "oidc_provider")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.requiredString("issuer", 500)
                .withDescription("OIDC issuer URL."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .addField(FieldDefinition.bool("isInternal").withColumnName("is_internal").withDefault(false)
                .withDescription("Whether this is the platform's own built-in provider."))
            .addField(FieldDefinition.string("clientId", 200).withColumnName("client_id")
                .withDescription("OAuth client identifier."))
            .addField(FieldDefinition.string("clientSecretEnc")
                .withColumnName("client_secret_enc").withServerOnly(true)
                .withDescription("Encrypted OAuth client secret; server-only, never returned."))
            .addField(FieldDefinition.string("audience", 200)
                .withDescription("Expected audience claim on tokens from this provider."))
            // OIDC Discovery endpoint overrides (auto-discovered from issuer when NULL)
            .addField(FieldDefinition.string("jwksUri", 500)
                .withColumnName("jwks_uri")
                .withDescription("URL of the provider's signing key set."))
            .addField(FieldDefinition.string("authorizationUri", 500)
                .withColumnName("authorization_uri")
                .withDescription("Provider authorization endpoint."))
            .addField(FieldDefinition.string("tokenUri", 500)
                .withColumnName("token_uri")
                .withDescription("Provider token endpoint."))
            .addField(FieldDefinition.string("userinfoUri", 500)
                .withColumnName("userinfo_uri")
                .withDescription("Provider userinfo endpoint."))
            .addField(FieldDefinition.string("endSessionUri", 500)
                .withColumnName("end_session_uri")
                .withDescription("Provider end-session (logout) endpoint."))
            .addField(FieldDefinition.string("discoveryStatus", 20)
                .withColumnName("discovery_status").withDefault("unknown")
                .withDescription("Outcome of the last discovery-document fetch."))
            // Claim mappings
            .addField(FieldDefinition.string("rolesClaim", 200)
                .withColumnName("roles_claim")
                .withDescription("Token claim carrying the user's roles."))
            .addField(FieldDefinition.string("rolesMapping", 200)
                .withColumnName("roles_mapping")
                .withDescription("JSON map from provider role to platform profile."))
            .addField(FieldDefinition.string("emailClaim", 200)
                .withColumnName("email_claim").withDefault("email")
                .withDescription("Token claim carrying the user's email address."))
            .addField(FieldDefinition.string("usernameClaim", 200)
                .withColumnName("username_claim").withDefault("preferred_username")
                .withDescription("Token claim carrying the user's username."))
            .addField(FieldDefinition.string("nameClaim", 200)
                .withColumnName("name_claim").withDefault("name")
                .withDescription("Token claim carrying the user's display name."))
            .addField(FieldDefinition.string("groupsClaim", 200)
                .withColumnName("groups_claim")
                .withDescription("Token claim carrying the user's groups."))
            .addField(FieldDefinition.text("groupsProfileMapping")
                .withColumnName("groups_profile_mapping")
                .withDescription("JSON map from provider group to platform profile."))
            .build();
    }

    public static CollectionDefinition samlProviders() {
        return systemBuilder("saml-providers", "SAML Providers", "saml_provider")
            .displayFieldName("name")
            .addField(FieldDefinition.requiredString("name", 100)
                .withDescription("Human-readable name."))
            .addField(FieldDefinition.requiredString("registrationId", 100)
                .withColumnName("registration_id")
                .withDescription("Identifier used in this provider's SAML endpoint URLs."))
            .addField(FieldDefinition.requiredString("idpEntityId", 500)
                .withColumnName("idp_entity_id")
                .withDescription("SAML entity id of the identity provider."))
            .addField(FieldDefinition.requiredString("ssoUrl", 500)
                .withColumnName("sso_url")
                .withDescription("Identity provider single sign-on URL."))
            // IdP signing certificate (PEM) — TEXT, no length bound.
            .addField(FieldDefinition.text("idpCertificate")
                .withColumnName("idp_certificate")
                .withDescription("Identity provider signing certificate, PEM encoded."))
            .addField(FieldDefinition.string("nameIdFormat", 200)
                .withColumnName("name_id_format")
                .withDescription("Expected SAML NameID format."))
            // IdP SingleLogoutService URL (optional). When set, SLO is advertised in
            // SP metadata + IdP-initiated LogoutRequests are honored.
            .addField(FieldDefinition.string("sloUrl", 500)
                .withColumnName("slo_url")
                .withDescription("Identity provider single logout URL."))
            // Assertion attribute → user-field mappings.
            .addField(FieldDefinition.string("emailAttribute", 200)
                .withColumnName("email_attribute")
                .withDescription("SAML attribute carrying the user's email address."))
            .addField(FieldDefinition.string("profileAttribute", 200)
                .withColumnName("profile_attribute")
                .withDescription("SAML attribute carrying the profile to assign."))
            .addField(FieldDefinition.bool("active").withDefault(true)
                .withDescription("Whether the record is active; inactive rows are ignored by the runtime."))
            .build();
    }

    public static CollectionDefinition connectedAppTokens() {
        return systemBuilder("connected-app-tokens", "Connected App Tokens",
                "connected_app_token")
            .tenantScoped(false)
            .addField(FieldDefinition.masterDetail("connectedAppId", "connected-apps",
                "Connected App").withColumnName("connected_app_id")
                .withDescription("Connected app this token was issued to."))
            .addField(FieldDefinition.requiredString("tokenHash", 200)
                .withColumnName("token_hash").withServerOnly(true)
                .withDescription("Hash of the issued token; server-only, never returned."))
            .addField(FieldDefinition.requiredJson("scopes")
                .withDescription("Space-separated OAuth scopes."))
            .addField(FieldDefinition.datetime("issuedAt")
                .withColumnName("issued_at").withNullable(false)
                .withDescription("When the token was issued."))
            .addField(FieldDefinition.datetime("expiresAt")
                .withColumnName("expires_at").withNullable(false)
                .withDescription("When this record stops being valid."))
            .addField(FieldDefinition.bool("revoked").withDefault(false)
                .withDescription("Whether the token has been revoked."))
            .addField(FieldDefinition.datetime("revokedAt")
                .withColumnName("revoked_at")
                .withDescription("When the token was revoked."))
            .build();
    }
}
