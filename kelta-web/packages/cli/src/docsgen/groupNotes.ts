/**
 * Free-text notes rendered under a `## <group>` heading in COMMANDS.md, for
 * context that doesn't fit a single command's one-line summary. Deliberately
 * separate from the manifest: this is human documentation only, not part of
 * the `kelta manifest` machine catalog pinned by manifest.test.ts.
 */
export const GROUP_NOTES: Record<string, string> = {
  auth:
    '`kelta auth login` writes the profile named by `--profile` (or `KELTA_PROFILE`), ' +
    'otherwise the default profile. Without an explicit profile, a `--tenant` that ' +
    "differs from the default profile's saved tenant is refused (exit `2`, " +
    '`PROFILE_TENANT_MISMATCH`) and nothing is changed — log in to the other tenant ' +
    'as its own profile with `--profile <name>`. Re-login to the same tenant, a ' +
    'profile with no saved tenant, and any explicit `--profile` are written as asked. ' +
    'The output names the profile that was written.',
  sandbox:
    'A sandbox is its own tenant with its own users, not a view into the parent ' +
    "tenant — the parent's PAT or session has no membership there and is refused " +
    'on sandbox-tenant paths. `sandbox create` prints a one-time admin credential ' +
    'for the new tenant plus its sign-in URL (`signInUrl`, `<ui-base-url>/<slug>/login`) ' +
    'and API base URL (`apiBaseUrl`, `<external-base-url>/<slug>`); the worker builds both ' +
    'from `kelta.ui-base-url` (`KELTA_UI_BASE_URL`) and `kelta.external-base-url` ' +
    '(`EXTERNAL_BASE_URL`). Sign in at the printed URL, or authenticate via ' +
    '`POST /auth/direct-login` on the auth server (not the API host) with `username`, ' +
    '`password` and `tenantSlug` from the printed output, or run ' +
    '`kelta auth login` against the sandbox slug (browser login) and then ' +
    '`kelta token create` to mint a PAT scoped to it.',
};
