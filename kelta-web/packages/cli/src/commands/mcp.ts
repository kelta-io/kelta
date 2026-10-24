import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { requireAuthenticated, resolveProfile } from '../config/resolve.js';
import { CliError, EXIT } from '../errors.js';
import { deriveMcpUrl, RemoteEndpoint, type Toolset } from '../mcp/remote.js';
import { buildMcpServer, connectServer, type McpSource } from '../mcp/server.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';
import { BUILD_TARGET } from '../version.js';

const TOOLSETS: Toolset[] = ['user', 'admin'];

/**
 * Command a GUI MCP client should launch. Desktop clients (Claude Desktop,
 * Cursor) are started by the OS launcher, NOT a login shell, so they do not
 * inherit `~/.local/bin` on PATH — a bare `kelta` fails to spawn with no
 * useful error. A released binary knows its own absolute path; a dev build
 * (node running dist/) falls back to the bare name, since execPath there is
 * the node interpreter.
 */
function launcherCommand(): string {
  return BUILD_TARGET === 'dev' ? 'kelta' : process.execPath;
}

function resolveToolsets(toolset: string): Toolset[] {
  return toolset === 'all' ? TOOLSETS : [toolset as Toolset];
}

export interface TenantSelector {
  /** Tenant slug to serve instead of the profile's (e.g. a sandbox). */
  tenant?: string;
  /** Name of the env var holding that tenant's PAT — never the token itself. */
  tokenEnv?: string;
}

/**
 * Point the whole server (bridged hosted tools AND `cli_` local tools) at the
 * selected tenant by writing it into `KELTA_TENANT`/`KELTA_TOKEN`, the env
 * layer {@link resolveProfile} already ranks above the profile file. Every
 * later resolution in this process — {@link resolveRemotes} and each local
 * tool's context — then targets that tenant with its own token.
 *
 * A PAT only works on the tenant it was issued in (the gateway rejects it
 * anywhere else), so `--tenant` without a token for that tenant fails here
 * instead of every tool call failing with 401.
 */
export function applyTenantSelector(
  profileFlag: string | undefined,
  selector: TenantSelector
): void {
  if (selector.tokenEnv !== undefined) {
    const token = process.env[selector.tokenEnv];
    if (!token) {
      throw new CliError(`--token-env ${selector.tokenEnv}: that environment variable is not set`, {
        code: 'MISSING_TOKEN_ENV',
        exitCode: EXIT.USAGE,
      });
    }
    process.env.KELTA_TOKEN = token;
  }
  if (selector.tenant === undefined) return;
  const profile = resolveProfile(profileFlag);
  if (
    selector.tokenEnv === undefined &&
    process.env.KELTA_TOKEN === undefined &&
    selector.tenant !== profile.tenantSlug
  ) {
    throw new CliError(
      `--tenant ${selector.tenant} needs that tenant's own token: pass --token-env <VAR> ` +
        `(or set KELTA_TOKEN). The token of profile "${profile.name}" is not valid on another tenant.`,
      { code: 'MISSING_TENANT_TOKEN', exitCode: EXIT.USAGE }
    );
  }
  process.env.KELTA_TENANT = selector.tenant;
}

export function resolveRemotes(
  profileFlag: string | undefined,
  toolset: string,
  mcpUrlFlag: string | undefined,
  source: McpSource
): RemoteEndpoint[] {
  if (source === 'local') return [];
  const profile = requireAuthenticated(resolveProfile(profileFlag));
  const mcpUrl = mcpUrlFlag ?? deriveMcpUrl(profile.apiUrl);
  if (!mcpUrl) {
    throw new CliError(
      `Cannot derive the hosted MCP URL from ${profile.apiUrl} — pass --mcp-url <your API origin>`,
      {
        code: 'MISSING_MCP_URL',
        exitCode: EXIT.USAGE,
        hint: "Fix the profile's API URL (`kelta profile show`) or pass --mcp-url.",
      }
    );
  }
  return resolveToolsets(toolset).map(
    (set) => new RemoteEndpoint(mcpUrl, profile.tenantSlug, set, profile.token)
  );
}

const serve = defineCommand({
  group: 'mcp',
  name: 'serve',
  summary: 'Run a stdio MCP server: hosted kelta-mcp tools bridged + cli_ local tools',
  requiresAuth: false, // source=local works offline; remote sources check the profile themselves
  ownsStdout: true, // stdout carries JSON-RPC frames only
  options: [
    { flag: '--toolset <set>', description: 'user|admin|all', default: 'all' },
    { flag: '--source <source>', description: 'auto|remote|local', default: 'auto' },
    {
      flag: '--mcp-url <url>',
      description: "Hosted MCP base URL (default: the profile's API origin)",
    },
    {
      flag: '--tenant <slug>',
      description: "Serve this tenant (e.g. a sandbox) instead of the profile's; needs its token",
    },
    {
      flag: '--token-env <var>',
      description: 'Env var holding the --tenant token (keeps it out of argv and client config)',
    },
    {
      flag: '--enable-api-tool',
      description: 'Expose the raw cli_api escape hatch (off by default)',
    },
  ],
  examples: [
    { cmd: 'kelta mcp serve', description: 'Serve all toolsets for the current profile' },
    {
      cmd: 'kelta mcp serve --toolset user --tenant acme-staging --token-env KELTA_STAGING_TOKEN',
      description: 'Serve the user toolset for a sandbox tenant',
    },
  ],
  input: z.object({
    toolset: z.enum(['user', 'admin', 'all']).default('all'),
    source: z.enum(['auto', 'remote', 'local']).default('auto'),
    mcpUrl: z.string().url().optional(),
    tenant: z.string().min(1).optional(),
    tokenEnv: z.string().min(1).optional(),
    enableApiTool: z.boolean().default(false),
  }),
  handler: async (ctx, input) => {
    // lazy import: registry.ts imports this module (cycle-safe at call time)
    const { allCommands } = await import('../registry/registry.js');
    applyTenantSelector(ctx.global.profile, { tenant: input.tenant, tokenEnv: input.tokenEnv });
    const remotes = resolveRemotes(ctx.global.profile, input.toolset, input.mcpUrl, input.source);
    const server = buildMcpServer({
      commands: allCommands,
      remotes,
      source: input.source,
      profileFlag: ctx.global.profile,
      enableApiTool: input.enableApiTool,
      log: ctx.log, // stderr only — stdout carries MCP frames exclusively
    });
    ctx.log(
      `kelta MCP server on stdio (toolset ${input.toolset}, source ${input.source}, ` +
        `${String(remotes.length)} hosted endpoint(s)` +
        `${input.tenant ? `, tenant ${input.tenant}` : ''})`
    );
    const transport = new StdioServerTransport();
    await connectServer(server, transport);
    // serve until the client closes stdin
    await new Promise<void>((resolve) => {
      transport.onclose = () => resolve();
      process.stdin.on('close', () => resolve());
    });
    return {};
  },
});

const install = defineCommand({
  group: 'mcp',
  name: 'install',
  summary: 'Print MCP client configuration for this profile (stdio bridge by default)',
  requiresAuth: false,
  positionals: [
    { name: 'client', description: 'claude-code|claude-desktop|cursor|generic', required: true },
  ],
  options: [
    { flag: '--toolset <set>', description: 'user|admin|all', default: 'all' },
    { flag: '--direct', description: 'Hosted HTTP config instead of the stdio bridge' },
    {
      flag: '--tenant <slug>',
      description: "Bridge this tenant (e.g. a sandbox) instead of the profile's",
    },
    {
      flag: '--token-env <var>',
      description:
        'Env var the launched server reads the --tenant token from (required with --tenant)',
    },
  ],
  examples: [
    { cmd: 'kelta mcp install claude-code' },
    {
      cmd: 'kelta mcp install cursor --toolset admin --tenant acme-staging --token-env KELTA_STAGING_TOKEN',
      description: 'Bridge a sandbox tenant with the admin toolset',
    },
  ],
  input: z.object({
    client: z.enum(['claude-code', 'claude-desktop', 'cursor', 'generic']),
    toolset: z.enum(['user', 'admin', 'all']).default('all'),
    direct: z.boolean().default(false),
    tenant: z.string().min(1).optional(),
    tokenEnv: z.string().min(1).optional(),
  }),
  handler: (ctx, input) => {
    const profile = resolveProfile(ctx.global.profile);
    if (input.tenant && !input.direct && !input.tokenEnv) {
      // the profile's token is rejected on another tenant; the config must name where the
      // tenant's own token comes from, and must never carry the token itself
      throw new CliError(
        "--tenant needs --token-env <VAR>: the env var holding that tenant's token",
        {
          code: 'MISSING_TOKEN_ENV',
          exitCode: EXIT.USAGE,
        }
      );
    }
    const tenantSlug = input.tenant ?? profile.tenantSlug;
    const name =
      `kelta-${profile.name}${input.tenant ? `-${input.tenant}` : ''}` +
      `${input.toolset === 'all' ? '' : `-${input.toolset}`}`;
    const serveArgs = [
      'mcp',
      'serve',
      '--profile',
      profile.name,
      ...(input.toolset === 'all' ? [] : ['--toolset', input.toolset]),
      ...(input.tenant ? ['--tenant', input.tenant] : []),
      ...(input.tokenEnv ? ['--token-env', input.tokenEnv] : []),
    ];

    if (input.direct) {
      // the hosted server has no "all": one endpoint per toolset
      const toolset = input.toolset === 'all' ? 'user' : input.toolset;
      const mcpUrl = profile.apiUrl ? deriveMcpUrl(profile.apiUrl) : undefined;
      const url = `${mcpUrl ?? '<api-origin>'}/${tenantSlug ?? '<tenant>'}/mcp/${toolset}`;
      const text =
        input.client === 'claude-code'
          ? `claude mcp add ${name} --transport http --url ${url} \\\n  --header "Authorization: Bearer <YOUR_PAT>"\n` +
            '# Replace <YOUR_PAT> with a token from: kelta token create --name mcp\n'
          : JSON.stringify(
              {
                mcpServers: {
                  [name]: {
                    type: 'http',
                    url,
                    headers: { Authorization: 'Bearer <YOUR_PAT>' },
                  },
                },
              },
              null,
              2
            ) + '\n# Replace <YOUR_PAT>; tokens come from: kelta token create --name mcp\n';
      return Promise.resolve({ text });
    }

    if (input.tokenEnv) {
      ctx.log(
        `Note: ${input.tokenEnv} must hold the ${tenantSlug ?? 'tenant'} token in the environment the client launches the server with.`
      );
    }
    const command = launcherCommand();
    const stdioConfig = JSON.stringify(
      { mcpServers: { [name]: { command, args: serveArgs } } },
      null,
      2
    );
    const text =
      input.client === 'claude-code'
        ? `claude mcp add ${name} -- ${command} ${serveArgs.join(' ')}\n`
        : input.client === 'claude-desktop'
          ? `# Merge into claude_desktop_config.json (Claude Desktop → Settings → Developer):\n${stdioConfig}\n`
          : input.client === 'cursor'
            ? `# Merge into .cursor/mcp.json:\n${stdioConfig}\n`
            : stdioConfig + '\n';
    return Promise.resolve({ text });
  },
});

export const mcpCommands: RegisteredCommand[] = [serve, install];
