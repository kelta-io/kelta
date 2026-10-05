import { z } from 'zod';
import { CliError, EXIT } from '../errors.js';
import { browserLogin, deriveAuthUrl } from '../auth/loginFlow.js';
import { loadConfig, removeToken, saveConfig, setToken } from '../config/store.js';
import { resolveProfile } from '../config/resolve.js';
import { defineCommand, type CommandContext, type RegisteredCommand } from '../registry/types.js';

const TOKEN_PREFIX_LENGTH = 8;

function explicitProfileName(ctx: CommandContext): string | undefined {
  return ctx.global.profile ?? process.env.KELTA_PROFILE;
}

function activeProfileName(ctx: CommandContext): string {
  return explicitProfileName(ctx) ?? loadConfig().defaultProfile ?? 'default';
}

interface SaveProfileArgs {
  name: string;
  apiUrl: string;
  tenantSlug: string;
  authUrl?: string;
  token: string;
  tokenExpiresAt?: string;
}

function saveAuthenticatedProfile(args: SaveProfileArgs): void {
  const config = loadConfig();
  config.profiles[args.name] = {
    ...config.profiles[args.name],
    apiUrl: args.apiUrl.replace(/\/$/, ''),
    tenantSlug: args.tenantSlug,
    ...(args.authUrl ? { authUrl: args.authUrl.replace(/\/$/, '') } : {}),
    tokenPrefix: args.token.slice(0, TOKEN_PREFIX_LENGTH),
    ...(args.tokenExpiresAt ? { tokenExpiresAt: args.tokenExpiresAt } : {}),
  };
  config.defaultProfile ??= args.name;
  saveConfig(config);
  setToken(args.name, args.token);
}

const login = defineCommand({
  group: 'auth',
  name: 'login',
  summary: 'Log in via the browser and store a PAT for a profile (--token for headless)',
  requiresAuth: false,
  options: [
    { flag: '--url <url>', description: 'Kelta API URL (default: from the profile)' },
    {
      flag: '--tenant <slug>',
      description:
        'Tenant slug (default: from the profile; a different tenant needs an explicit --profile)',
    },
    {
      flag: '--auth-url <url>',
      description: 'Auth server URL (default: profile value, or api.→auth. host derivation)',
    },
    { flag: '--token <token>', description: 'Skip the browser: store this PAT (klt_...) or JWT' },
    {
      flag: '--expires-in <days>',
      description: 'PAT lifetime in days (browser login)',
      default: '90',
    },
    { flag: '--no-browser', description: 'Print the login URL instead of opening a browser' },
  ],
  input: z.object({
    url: z.string().url().optional(),
    tenant: z.string().min(1).optional(),
    authUrl: z.string().url().optional(),
    token: z.string().min(1).optional(),
    expiresIn: z.coerce.number().int().min(1).max(365).default(90),
    browser: z.boolean().default(true),
  }),
  handler: async (ctx, input) => {
    const name = activeProfileName(ctx);
    const existing = loadConfig().profiles[name];
    let apiUrl = input.url ?? existing?.apiUrl;
    const tenantSlug = input.tenant ?? existing?.tenantSlug;
    // Without an explicit --profile/KELTA_PROFILE the active profile is only
    // implied; never let a login for another tenant replace its credential.
    if (
      !explicitProfileName(ctx) &&
      input.tenant &&
      existing?.tenantSlug &&
      existing.tenantSlug !== input.tenant
    ) {
      throw new CliError(
        `Profile "${name}" is bound to tenant "${existing.tenantSlug}", not "${input.tenant}" — ` +
          `nothing was changed. Log in to "${input.tenant}" as its own profile: ` +
          `kelta auth login --tenant ${input.tenant} --profile ${input.tenant}`,
        { code: 'PROFILE_TENANT_MISMATCH', exitCode: EXIT.USAGE }
      );
    }
    if (!apiUrl || !tenantSlug) {
      throw new CliError(`Profile "${name}" has no saved connection — pass --url and --tenant`, {
        code: 'MISSING_CONNECTION',
        exitCode: EXIT.USAGE,
      });
    }
    // The API URL must be an origin: the CLI prepends /<tenant> itself, so a
    // path here (e.g. --url https://api.kelta.io/spotopened) doubles the slug
    // and 404s at the gateway.
    const parsedApiUrl = new URL(apiUrl);
    if (parsedApiUrl.pathname !== '/' && parsedApiUrl.pathname !== '') {
      ctx.log(
        `Ignoring path "${parsedApiUrl.pathname}" in the API URL — the tenant slug comes from --tenant`
      );
      apiUrl = parsedApiUrl.origin;
    }

    // Headless path: store the supplied token verbatim, exactly as before.
    if (input.token) {
      saveAuthenticatedProfile({
        name,
        apiUrl,
        tenantSlug,
        authUrl: input.authUrl,
        token: input.token,
      });
      return {
        data: {
          profile: name,
          url: apiUrl.replace(/\/$/, ''),
          tenant: tenantSlug,
          method: 'token',
        },
        message: `Authenticated profile "${name}" (tenant "${tenantSlug}")`,
      };
    }

    const authUrl = input.authUrl ?? existing?.authUrl ?? deriveAuthUrl(apiUrl);
    if (!authUrl) {
      throw new CliError(
        `Cannot derive the auth server from ${apiUrl} — pass --auth-url (e.g. https://auth.kelta.io)`,
        { code: 'MISSING_AUTH_URL', exitCode: EXIT.USAGE }
      );
    }

    const minted = await browserLogin({
      apiUrl,
      tenantSlug,
      authUrl,
      expiresInDays: input.expiresIn,
      noBrowser: !input.browser,
      log: ctx.log,
    });
    saveAuthenticatedProfile({
      name,
      apiUrl,
      tenantSlug,
      authUrl,
      token: minted.token,
      tokenExpiresAt: minted.expiresAt,
    });
    return {
      data: {
        profile: name,
        url: apiUrl.replace(/\/$/, ''),
        tenant: tenantSlug,
        method: 'browser',
        tokenPrefix: minted.tokenPrefix,
        tokenName: minted.name,
        expiresAt: minted.expiresAt,
      },
      message:
        `Logged in — created PAT ${minted.tokenPrefix}… ` +
        `(expires ${minted.expiresAt || 'unknown'}) → profile "${name}" (tenant "${tenantSlug}")`,
    };
  },
});

const logout = defineCommand({
  group: 'auth',
  name: 'logout',
  summary: 'Remove the stored credential for the active profile (--revoke also revokes it)',
  requiresAuth: false,
  options: [
    { flag: '--revoke', description: 'Revoke the PAT server-side before removing it locally' },
  ],
  input: z.object({ revoke: z.boolean().default(false) }),
  handler: async (ctx, input) => {
    const name = activeProfileName(ctx);
    let revoked = false;

    if (input.revoke) {
      const profile = resolveProfile(name);
      if (!profile.token?.startsWith('klt_')) {
        throw new CliError('No stored PAT to revoke for this profile', {
          code: 'NO_PAT_TO_REVOKE',
          exitCode: EXIT.USAGE,
        });
      }
      const prefix = profile.token.slice(0, TOKEN_PREFIX_LENGTH);
      const tokens = (await ctx.client.admin.personalTokens.list()) as {
        id: string;
        tokenPrefix: string;
      }[];
      const match = tokens.find((token) => token.tokenPrefix === prefix);
      if (!match) {
        ctx.log(`Warning: no server-side token matches prefix ${prefix}… — removing locally only`);
      } else {
        await ctx.client.admin.personalTokens.revoke(match.id);
        revoked = true;
      }
    }

    removeToken(name);
    const config = loadConfig();
    if (config.profiles[name]) {
      delete config.profiles[name].tokenPrefix;
      delete config.profiles[name].tokenExpiresAt;
      saveConfig(config);
    }
    return {
      data: { profile: name, revoked },
      message: `Credential removed for profile "${name}"${revoked ? ' (revoked server-side)' : ''}`,
    };
  },
});

const status = defineCommand({
  group: 'auth',
  name: 'status',
  summary: 'Show the resolved connection settings for the active profile',
  requiresAuth: false,
  input: z.object({}),
  handler: (ctx) => {
    const profile = resolveProfile(ctx.global.profile);
    const data = {
      profile: profile.name,
      url: profile.apiUrl ?? null,
      tenant: profile.tenantSlug ?? null,
      authUrl: profile.authUrl ?? null,
      token: profile.token ? profile.token.slice(0, TOKEN_PREFIX_LENGTH) + '…' : null,
      tokenExpiresAt: profile.tokenExpiresAt ?? null,
      authenticated: Boolean(profile.apiUrl && profile.tenantSlug && profile.token),
    };
    const human = [
      `Profile: ${data.profile}`,
      `URL:     ${data.url ?? '(not set)'}`,
      `Tenant:  ${data.tenant ?? '(not set)'}`,
      `Token:   ${data.token ?? '(not set)'}`,
      ...(data.tokenExpiresAt ? [`Expires: ${data.tokenExpiresAt}`] : []),
    ].join('\n');
    return Promise.resolve({ data, human: human + '\n' });
  },
});

export const authCommands: RegisteredCommand[] = [login, logout, status];
