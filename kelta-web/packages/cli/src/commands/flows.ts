import { z } from 'zod';
import { readDataArgument } from '../data.js';
import { CliError, EXIT } from '../errors.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const POLL_INTERVAL_MS = 2000;
const MAX_POLL_ATTEMPTS = 150;
const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

export const STEP_LOGGING_LEVELS = ['FULL', 'ERRORS_ONLY', 'NONE'] as const;

const STEP_LOGGING_OPTION = {
  flag: '--step-logging <level>',
  description:
    'FULL (every step with snapshots) | ERRORS_ONLY (snapshots only for a failed step) | NONE (only a failed step)',
};

/** Case-insensitive `--step-logging`; anything outside the three levels fails before the request. */
const stepLoggingInput = z.preprocess(
  (value) => (typeof value === 'string' ? value.trim().toUpperCase() : value),
  z.enum(STEP_LOGGING_LEVELS).optional()
);

const RETENTION_DAYS_OPTION = {
  flag: '--retention-days <days>',
  description:
    'Days a finished run and its step logs are kept, 1-3650 (default: the platform-wide window)',
};

/**
 * `--retention-days`: a whole number of days, 1-3650. On update, `default` clears the
 * override (sends null) so the platform-wide window applies again.
 */
function retentionDaysInput(allowDefault: boolean) {
  return z.preprocess(
    (value) => {
      if (typeof value !== 'string') return value;
      const trimmed = value.trim();
      if (allowDefault && trimmed.toLowerCase() === 'default') return null;
      return /^-?\d+$/.test(trimmed) ? Number(trimmed) : trimmed;
    },
    allowDefault
      ? z.number().int().min(1).max(3650).nullable().optional()
      : z.number().int().min(1).max(3650).optional()
  );
}

interface ExecutionBody {
  data?: { id?: string; attributes?: { status?: string } };
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const list = defineCommand({
  group: 'flows',
  name: 'list',
  summary: 'List flow definitions',
  examples: [{ cmd: 'kelta flows list --output json' }],
  input: z.object({}),
  handler: async (ctx) => {
    const response = await ctx.client.getAxiosInstance().get<unknown>('/api/flows');
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'flowType', header: 'TYPE' },
        { key: 'active', header: 'ACTIVE' },
        { key: 'stepLogging', header: 'STEP LOGGING' },
        { key: 'retentionDays', header: 'RETENTION DAYS' },
      ],
    };
  },
});

const describe = defineCommand({
  group: 'flows',
  name: 'describe',
  summary: 'Show a flow definition',
  positionals: [{ name: 'flowId', description: 'Flow id', required: true }],
  examples: [{ cmd: 'kelta flows describe flow_123' }],
  input: z.object({ flowId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client.getAxiosInstance().get<unknown>(`/api/flows/${input.flowId}`);
    return { data: response.data };
  },
});

const create = defineCommand({
  group: 'flows',
  name: 'create',
  summary: 'Create a flow (definition JSON is the state machine)',
  options: [
    { flag: '--name <name>', description: 'Flow name (required)' },
    {
      flag: '--type <type>',
      description: 'RECORD_TRIGGERED|SCHEDULED|AUTOLAUNCHED|SCREEN|NATS_TRIGGERED',
    },
    { flag: '--definition <json>', description: 'Definition as JSON, @file, or - (required)' },
    { flag: '--description <text>', description: 'Description' },
    { flag: '--trigger-config <json>', description: 'Trigger config as JSON, @file, or -' },
    { flag: '--active', description: 'Create the flow active (default: inactive)' },
    { ...STEP_LOGGING_OPTION, description: `${STEP_LOGGING_OPTION.description} (default: FULL)` },
    RETENTION_DAYS_OPTION,
  ],
  examples: [
    {
      cmd: 'kelta flows create --name \'Notify on new order\' --type RECORD_TRIGGERED --definition @order-flow.json --trigger-config \'{"collection": "orders", "events": ["CREATE"]}\' --active',
      description: 'Active flow that runs whenever an order is created',
    },
    {
      cmd: "kelta flows create --name 'Nightly cleanup' --type SCHEDULED --definition @cleanup.json --step-logging ERRORS_ONLY --retention-days 30",
      description: 'Scheduled flow that keeps runs for 30 days',
    },
  ],
  input: z.object({
    name: z.string().min(1),
    type: z.string().min(1),
    definition: z.string().min(1),
    description: z.string().optional(),
    triggerConfig: z.string().optional(),
    active: z.boolean().default(false),
    stepLogging: stepLoggingInput,
    retentionDays: retentionDaysInput(false),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {
      name: input.name,
      flowType: input.type.toUpperCase(),
      definition: readDataArgument(input.definition),
      active: input.active,
    };
    if (input.description) attributes.description = input.description;
    if (input.triggerConfig) attributes.triggerConfig = readDataArgument(input.triggerConfig);
    if (input.stepLogging) attributes.stepLogging = input.stepLogging;
    if (input.retentionDays !== undefined) attributes.retentionDays = input.retentionDays;
    const response = await ctx.client
      .getAxiosInstance()
      .post<{ data?: { id?: string } }>('/api/flows', {
        data: { type: 'flows', attributes },
      });
    return {
      data: response.data,
      message: `Flow "${input.name}" created (${input.active ? 'active' : 'inactive'})`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const update = defineCommand({
  group: 'flows',
  name: 'update',
  summary: 'Update a flow by id',
  positionals: [{ name: 'flowId', description: 'Flow id', required: true }],
  options: [
    { flag: '--active <bool>', description: 'true|false' },
    { flag: '--definition <json>', description: 'Definition as JSON, @file, or -' },
    STEP_LOGGING_OPTION,
    {
      flag: '--retention-days <days>',
      description: `${RETENTION_DAYS_OPTION.description}; "default" clears the override`,
    },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  examples: [
    { cmd: 'kelta flows update flow_123 --active false', description: 'Deactivate a flow' },
    {
      cmd: 'kelta flows update flow_123 --definition @order-flow.json --retention-days default',
      description: 'Replace the definition and reset retention to the platform default',
    },
  ],
  input: z.object({
    flowId: z.string().min(1),
    active: z.enum(['true', 'false']).optional(),
    definition: z.string().optional(),
    stepLogging: stepLoggingInput,
    retentionDays: retentionDaysInput(true),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.active !== undefined) attributes.active = input.active === 'true';
    if (input.definition) attributes.definition = readDataArgument(input.definition);
    if (input.stepLogging) attributes.stepLogging = input.stepLogging;
    if (input.retentionDays !== undefined) attributes.retentionDays = input.retentionDays;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/flows/${input.flowId}`, {
        data: { type: 'flows', id: input.flowId, attributes },
      });
    return { data: response.data, message: `Flow ${input.flowId} updated` };
  },
});

const execute = defineCommand({
  group: 'flows',
  name: 'execute',
  summary: 'Start a flow run (flows read input as $.input.<key>)',
  positionals: [{ name: 'flowId', description: 'Flow id', required: true }],
  options: [
    { flag: '--input <json>', description: 'Flow input as JSON, @file, or -' },
    { flag: '--test', description: 'Mark the run as a test execution' },
    { flag: '--wait', description: 'Poll until COMPLETED/FAILED/CANCELLED (~5 min budget)' },
  ],
  examples: [
    {
      cmd: 'kelta flows execute flow_123 --input \'{"orderId": "rec_123"}\' --wait',
      description: 'Run with input read as $.input.orderId and wait for the result',
    },
    {
      cmd: 'kelta flows execute flow_123 --input @input.json --test',
      description: 'Start a test run from a file',
    },
  ],
  input: z.object({
    flowId: z.string().min(1),
    input: z.string().optional(),
    test: z.boolean().default(false),
    wait: z.boolean().default(false),
  }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const body: Record<string, unknown> = {};
    if (input.input) body.input = readDataArgument(input.input);
    if (input.test) body.test = true;
    const started = await axios.post<ExecutionBody>(`/api/flows/${input.flowId}/execute`, body);
    const executionId = started.data.data?.id;
    if (!input.wait || !executionId) {
      return {
        data: started.data,
        message: `Execution ${executionId ?? '?'} started (check: kelta flows run ${executionId ?? '<id>'})`,
        ids: executionId ? [executionId] : [],
      };
    }

    for (let attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
      const current = await axios.get<ExecutionBody>(`/api/flows/executions/${executionId}`);
      const status = current.data.data?.attributes?.status ?? '';
      if (TERMINAL_STATUSES.has(status)) {
        return {
          data: current.data,
          message: `Execution ${executionId} ${status}`,
          ids: [executionId],
        };
      }
      await sleep(POLL_INTERVAL_MS);
    }
    throw new CliError(`Timed out waiting for execution ${executionId} to finish`, {
      code: 'EXECUTION_TIMEOUT',
      exitCode: EXIT.API,
    });
  },
});

const remove = defineCommand({
  group: 'flows',
  name: 'delete',
  summary: 'Delete a flow (the server refuses flows that have runs)',
  dangerous: true,
  positionals: [{ name: 'flowId', description: 'Flow id', required: true }],
  examples: [{ cmd: 'kelta flows delete flow_123 --yes' }],
  input: z.object({ flowId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.getAxiosInstance().delete(`/api/flows/${input.flowId}`);
    return {
      data: { deleted: true, id: input.flowId },
      message: `Flow ${input.flowId} deleted`,
      ids: [input.flowId],
    };
  },
});

const runs = defineCommand({
  group: 'flows',
  name: 'runs',
  summary: 'List recent executions of a flow',
  positionals: [{ name: 'flowId', description: 'Flow id', required: true }],
  options: [{ flag: '--limit <n>', description: 'Max runs', default: '50' }],
  examples: [{ cmd: 'kelta flows runs flow_123 --limit 10' }],
  input: z.object({
    flowId: z.string().min(1),
    limit: z.coerce.number().int().positive().default(50),
  }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/flows/${input.flowId}/flow-executions?limit=${String(input.limit)}`);
    return {
      data: response.data,
      columns: [
        { key: 'status', header: 'STATUS' },
        { key: 'startedAt', header: 'STARTED' },
        { key: 'durationMs', header: 'DURATION MS' },
        { key: 'stepCount', header: 'STEPS' },
      ],
    };
  },
});

const run = defineCommand({
  group: 'flows',
  name: 'run',
  summary: 'Show one execution (--steps for per-step detail)',
  positionals: [{ name: 'executionId', description: 'Execution id', required: true }],
  options: [{ flag: '--steps', description: 'Include per-step detail' }],
  examples: [
    {
      cmd: 'kelta flows run exec_123 --steps',
      description: 'Show an execution with per-step detail',
    },
  ],
  input: z.object({ executionId: z.string().min(1), steps: z.boolean().default(false) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const path = input.steps
      ? `/api/flows/executions/${input.executionId}/steps`
      : `/api/flows/executions/${input.executionId}`;
    const response = await axios.get<unknown>(path);
    return { data: response.data };
  },
});

const cancel = defineCommand({
  group: 'flows',
  name: 'cancel',
  summary: 'Cancel a running execution',
  dangerous: true,
  positionals: [{ name: 'executionId', description: 'Execution id', required: true }],
  examples: [{ cmd: 'kelta flows cancel exec_123 --yes' }],
  input: z.object({ executionId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .post<unknown>(`/api/flows/executions/${input.executionId}/cancel`);
    return { data: response.data, message: `Execution ${input.executionId} cancelled` };
  },
});

const retry = defineCommand({
  group: 'flows',
  name: 'retry',
  summary: 'Retry an execution (new run)',
  positionals: [{ name: 'executionId', description: 'Execution id', required: true }],
  options: [{ flag: '--mode <mode>', description: 'full|from-failure', default: 'full' }],
  examples: [
    {
      cmd: 'kelta flows retry exec_123 --mode from-failure',
      description: 'Resume from the failed step',
    },
  ],
  input: z.object({
    executionId: z.string().min(1),
    mode: z.enum(['full', 'from-failure']).default('full'),
  }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .post<unknown>(`/api/flows/executions/${input.executionId}/retry?mode=${input.mode}`);
    return { data: response.data, message: `Retry (${input.mode}) started` };
  },
});

const publish = defineCommand({
  group: 'flows',
  name: 'publish',
  summary: 'Publish the current flow definition as a new version',
  positionals: [{ name: 'flowId', description: 'Flow id', required: true }],
  options: [{ flag: '--summary <text>', description: 'Change summary' }],
  examples: [{ cmd: "kelta flows publish flow_123 --summary 'Add approval step'" }],
  input: z.object({ flowId: z.string().min(1), summary: z.string().optional() }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .post<unknown>(
        `/api/flows/${input.flowId}/publish`,
        input.summary ? { changeSummary: input.summary } : {}
      );
    return { data: response.data, message: `Flow ${input.flowId} published` };
  },
});

const versions = defineCommand({
  group: 'flows',
  name: 'versions',
  summary: 'List published versions of a flow',
  positionals: [{ name: 'flowId', description: 'Flow id', required: true }],
  examples: [{ cmd: 'kelta flows versions flow_123' }],
  input: z.object({ flowId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/flows/${input.flowId}/versions`);
    return { data: response.data };
  },
});

const webhookUrl = defineCommand({
  group: 'flows',
  name: 'webhook-url',
  summary: 'Show the inbound webhook URL of an AUTOLAUNCHED flow',
  positionals: [{ name: 'flowId', description: 'Flow id', required: true }],
  examples: [{ cmd: 'kelta flows webhook-url flow_123' }],
  input: z.object({ flowId: z.string().min(1) }),
  handler: async (ctx, input) => {
    const response = await ctx.client
      .getAxiosInstance()
      .get<unknown>(`/api/flows/${input.flowId}/webhook-url`);
    return { data: response.data };
  },
});

export const flowCommands: RegisteredCommand[] = [
  list,
  describe,
  create,
  update,
  remove,
  execute,
  runs,
  run,
  cancel,
  retry,
  publish,
  versions,
  webhookUrl,
];
