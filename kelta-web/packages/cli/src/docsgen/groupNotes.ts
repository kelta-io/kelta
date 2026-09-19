/**
 * Free-text notes rendered under a `## <group>` heading in COMMANDS.md, for
 * context that doesn't fit a single command's one-line summary. Deliberately
 * separate from the manifest: this is human documentation only, not part of
 * the `kelta manifest` machine catalog pinned by manifest.test.ts.
 */
export const GROUP_NOTES: Record<string, string> = {
  sandbox:
    'A sandbox is its own tenant with its own users, not a view into the parent ' +
    "tenant — the parent's PAT or session has no membership there and is refused " +
    'on sandbox-tenant paths. `sandbox create` prints a one-time admin credential ' +
    'for the new tenant; authenticate with it via `POST /auth/direct-login` ' +
    '(`username`, `password`, `tenantSlug` from the printed output), or run ' +
    '`kelta auth login` against the sandbox slug (browser login) and then ' +
    '`kelta token create` to mint a PAT scoped to it.',
};
