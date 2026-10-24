import { z } from 'zod';
import { collectionIdByName } from '../admin/lookups.js';
import { readDataArgument } from '../data.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

const list = defineCommand({
  group: 'validation-rules',
  name: 'list',
  summary: 'List validation rules of a collection',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  examples: [{ cmd: 'kelta validation-rules list invoices' }],
  input: z.object({ collection: z.string().min(1) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const collectionId = await collectionIdByName(axios, input.collection);
    const response = await axios.get<unknown>(
      `/api/validation-rules?filter[collectionId][eq]=${collectionId}`
    );
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'errorConditionFormula', header: 'ERROR WHEN TRUE' },
        { key: 'errorMessage', header: 'MESSAGE' },
        { key: 'active', header: 'ACTIVE' },
        { key: 'severity', header: 'SEVERITY' },
      ],
    };
  },
});

const create = defineCommand({
  group: 'validation-rules',
  name: 'create',
  summary: 'Create a validation rule (record is REJECTED when --formula evaluates TRUE)',
  positionals: [{ name: 'collection', description: 'Collection name or id', required: true }],
  options: [
    { flag: '--name <name>', description: 'Rule name (required)' },
    {
      flag: '--formula <formula>',
      description: 'ERROR condition — TRUE rejects the record (required)',
    },
    { flag: '--message <text>', description: 'Error message shown to the user (required)' },
    {
      flag: '--evaluate-on <when>',
      description: 'CREATE|UPDATE|CREATE_AND_UPDATE',
      default: 'CREATE_AND_UPDATE',
    },
    { flag: '--severity <level>', description: 'ERROR|WARNING', default: 'ERROR' },
    { flag: '--error-field <field>', description: 'Field to attach the error to' },
    { flag: '--inactive', description: 'Create the rule inactive' },
  ],
  examples: [
    {
      cmd: "kelta validation-rules create invoices --name positive_amount --formula 'amount <= 0' --message 'Amount must be positive' --error-field amount",
      description: 'Reject invoices whose amount is zero or negative',
    },
    {
      cmd: "kelta validation-rules create orders --name due_date_set --formula 'ISBLANK(due_date)' --message 'Set a due date' --evaluate-on UPDATE --severity WARNING",
      description: 'Warn on update when the due date is blank',
    },
  ],
  input: z.object({
    collection: z.string().min(1),
    name: z.string().min(1),
    formula: z.string().min(1),
    message: z.string().min(1),
    evaluateOn: z.enum(['CREATE', 'UPDATE', 'CREATE_AND_UPDATE']).default('CREATE_AND_UPDATE'),
    severity: z.enum(['ERROR', 'WARNING']).default('ERROR'),
    errorField: z.string().optional(),
    inactive: z.boolean().default(false),
  }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const collectionId = await collectionIdByName(axios, input.collection);
    const attributes: Record<string, unknown> = {
      collectionId,
      name: input.name,
      errorConditionFormula: input.formula,
      errorMessage: input.message,
      active: !input.inactive,
      evaluateOn: input.evaluateOn,
      severity: input.severity,
    };
    if (input.errorField) attributes.errorField = input.errorField;
    const response = await axios.post<{ data?: { id?: string } }>('/api/validation-rules', {
      data: { type: 'validation-rules', attributes },
    });
    return {
      data: response.data,
      message: `Validation rule "${input.name}" created on ${input.collection}`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const update = defineCommand({
  group: 'validation-rules',
  name: 'update',
  summary: 'Update a validation rule by id',
  positionals: [{ name: 'ruleId', description: 'Rule id', required: true }],
  options: [
    { flag: '--active <bool>', description: 'true|false' },
    { flag: '--data <json>', description: 'Attributes as JSON, @file, or - (merged last)' },
  ],
  examples: [
    {
      cmd: 'kelta validation-rules update rule_123 --active false',
      description: 'Deactivate a rule',
    },
    {
      cmd: 'kelta validation-rules update rule_123 --data \'{"errorMessage": "Amount must be greater than zero"}\'',
    },
  ],
  input: z.object({
    ruleId: z.string().min(1),
    active: z.enum(['true', 'false']).optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.active !== undefined) attributes.active = input.active === 'true';
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/validation-rules/${input.ruleId}`, {
        data: { type: 'validation-rules', id: input.ruleId, attributes },
      });
    return { data: response.data, message: `Validation rule ${input.ruleId} updated` };
  },
});

const remove = defineCommand({
  group: 'validation-rules',
  name: 'delete',
  summary: 'Delete a validation rule',
  dangerous: true,
  positionals: [{ name: 'ruleId', description: 'Rule id', required: true }],
  examples: [{ cmd: 'kelta validation-rules delete rule_123 --yes' }],
  input: z.object({ ruleId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.getAxiosInstance().delete(`/api/validation-rules/${input.ruleId}`);
    return {
      data: { deleted: true, id: input.ruleId },
      message: `Validation rule ${input.ruleId} deleted`,
      ids: [input.ruleId],
    };
  },
});

export const validationCommands: RegisteredCommand[] = [list, create, update, remove];
