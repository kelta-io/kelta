---
title: Single sign-on (OIDC and SAML)
description: Federate authentication to an external identity provider — OIDC providers with claim mapping, SAML 2.0 with single logout, and just-in-time provisioning.
section: security
order: 20
---

Kelta's auth server brokers logins to external identity providers. Users click the provider on the login page,
authenticate there, and come back with a Kelta session. Accounts are created just-in-time on first login.

## OIDC providers

Create a record in the `oidc-providers` system collection (Setup → Administration → OIDC providers, or CLI/MCP):

| Attribute | Meaning |
|---|---|
| `name`, `issuer`, `clientId`, `clientSecretEnc` | The provider and the client you registered there. |
| `discoveryStatus`, `jwksUri`, `authorizationUri`, `tokenUri`, `userinfoUri`, `endSessionUri` | Filled from `/.well-known/openid-configuration`; override if the provider is non-standard. |
| `audience` | Expected `aud` claim. |
| `emailClaim`, `usernameClaim`, `nameClaim` | Which claims populate the Kelta user (defaults: `email`, `preferred_username`, `name`). |
| `groupsClaim`, `groupsProfileMapping` | Map provider groups to a Kelta profile on login. |
| `rolesClaim`, `rolesMapping` | Same for roles. |
| `active` | Only active providers appear on the login page. |

The redirect URI to register at the provider is `https://auth.example.com/login/oauth2/code/<providerId>`.
Google, Microsoft Entra ID, Okta and any other compliant issuer are configured the same way — there are no
vendor-specific presets.

## SAML 2.0 providers

Create a record in `saml-providers`:

| Attribute | Meaning |
|---|---|
| `registrationId` | Short id that forms the SP endpoints below. |
| `idpEntityId`, `ssoUrl`, `idpCertificate` (PEM) | From the IdP's metadata. |
| `nameIdFormat` | Requested NameID format. |
| `emailAttribute`, `profileAttribute` | Assertion attributes that carry the email and the Kelta profile name. |
| `sloUrl` | Optional; enables Single Logout in both directions. |
| `active` | |

Service-provider endpoints, per provider:

| Purpose | URL |
|---|---|
| SP metadata | `https://auth.example.com/saml2/service-provider-metadata/<registrationId>` |
| Assertion consumer (ACS) | `https://auth.example.com/login/saml2/sso/<registrationId>` |
| Single logout | `https://auth.example.com/logout/saml2/slo/<registrationId>` |

Both SP-initiated and IdP-initiated SSO work. Outbound `AuthnRequest`s and `LogoutRequest`s are signed with a
platform-wide SP key pair configured by the operator. Assertion encryption is not currently supported — configure
the IdP to sign, not encrypt, assertions.

Configuration changes are picked up live (the auth server caches provider metadata for five minutes).

## Just-in-time provisioning

On the first federated login the user is created with:

1. email from `emailClaim` / `emailAttribute` (falling back to standard claims or the SAML NameID),
2. a profile resolved from `groupsProfileMapping` / `rolesMapping` / `profileAttribute`, else the seeded
   **Minimum Access** profile,
3. `status: ACTIVE`, and an invitation email so the user knows an account now exists.

Later logins re-resolve the profile from the current claims. Deactivating the user in Kelta blocks the login even if the
IdP still asserts it.

## Groups

The provider's group claim is used to **choose a profile** (`groupsProfileMapping`), not to populate Kelta groups.
To mirror directory groups into `user-groups` for sharing and queue assignment, provision them with
[SCIM](/docs/security/scim/) — the SCIM `externalId` of a group is stored on `oidcGroupName`.

## SCIM instead of JIT

If your IdP supports SCIM, provision users and groups ahead of login — see
[SCIM 2.0 provisioning](/docs/security/scim/). JIT and SCIM can coexist; SCIM wins on attribute conflicts because it
writes the user record directly.
