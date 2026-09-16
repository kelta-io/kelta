import { z } from 'zod';
import { menuIdByName } from '../admin/lookups.js';
import { readDataArgument } from '../data.js';
import { CliError, EXIT } from '../errors.js';
import { defineCommand, type RegisteredCommand } from '../registry/types.js';

interface MenuTreeCounts {
  menuId?: string;
  name?: string;
  created?: number;
  updated?: number;
  deleted?: number;
  unchanged?: number;
}

interface MenuItemResource {
  id: string;
  attributes?: Record<string, unknown>;
}

interface MenuItemNode {
  id: string;
  label?: unknown;
  path?: unknown;
  icon?: unknown;
  displayOrder: number;
  active?: unknown;
  children: MenuItemNode[];
}

/** Nests flat `ui-menu-items` rows by `parentId` (submenus/group headers — V166). */
function buildMenuTree(items: MenuItemResource[]): MenuItemNode[] {
  const nodes = new Map<string, MenuItemNode>();
  for (const item of items) {
    nodes.set(item.id, {
      id: item.id,
      label: item.attributes?.label,
      path: item.attributes?.path,
      icon: item.attributes?.icon,
      displayOrder: Number(item.attributes?.displayOrder ?? 0),
      active: item.attributes?.active,
      children: [],
    });
  }
  const roots: MenuItemNode[] = [];
  for (const item of items) {
    const node = nodes.get(item.id)!;
    const parentId = item.attributes?.parentId;
    const parent = typeof parentId === 'string' ? nodes.get(parentId) : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  }
  const sortTree = (list: MenuItemNode[]): void => {
    list.sort((a, b) => a.displayOrder - b.displayOrder);
    for (const node of list) sortTree(node.children);
  };
  sortTree(roots);
  return roots;
}

const menuList = defineCommand({
  group: 'menus',
  name: 'list',
  summary: 'List UI menus (apps/nav v2)',
  input: z.object({}),
  handler: async (ctx) => {
    const response = await ctx.client.getAxiosInstance().get<unknown>('/api/ui-menus');
    return {
      data: response.data,
      columns: [
        { key: 'name', header: 'NAME' },
        { key: 'displayOrder', header: 'ORDER' },
        { key: 'isDefault', header: 'DEFAULT' },
        { key: 'active', header: 'ACTIVE' },
      ],
    };
  },
});

const menuGet = defineCommand({
  group: 'menus',
  name: 'get',
  summary: 'Get a UI menu by name or id (--tree nests items by parentId)',
  positionals: [{ name: 'menu', description: 'Menu name or id', required: true }],
  options: [
    {
      flag: '--tree',
      description: 'Include menu items nested by parentId under attribute "items"',
    },
  ],
  input: z.object({ menu: z.string().min(1), tree: z.boolean().default(false) }),
  handler: async (ctx, input) => {
    const axios = ctx.client.getAxiosInstance();
    const id = await menuIdByName(axios, input.menu);
    const response = await axios.get<{
      data?: { id: string; type?: string; attributes?: Record<string, unknown> };
    }>(`/api/ui-menus/${id}`);
    if (!input.tree) return { data: response.data };

    const itemsResponse = await axios.get<{ data?: MenuItemResource[] }>(
      `/api/ui-menu-items?filter[menuId][eq]=${id}&page[size]=200`
    );
    const items = buildMenuTree(itemsResponse.data.data ?? []);
    const menu = response.data.data;
    return {
      data: {
        data: {
          type: 'ui-menus',
          id: menu?.id,
          attributes: { ...menu?.attributes, items },
        },
      },
    };
  },
});

const menuCreate = defineCommand({
  group: 'menus',
  name: 'create',
  summary: 'Create a UI menu',
  options: [
    { flag: '--name <name>', description: 'Menu name (required)' },
    { flag: '--description <text>', description: 'Description' },
    { flag: '--icon <name>', description: 'Icon rendered beside the app label' },
    { flag: '--display-order <n>', description: 'Ordinal position among sibling menus' },
    { flag: '--default', description: 'Mark as the default menu' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    name: z.string().min(1),
    description: z.string().optional(),
    icon: z.string().optional(),
    displayOrder: z.coerce.number().int().optional(),
    default: z.boolean().default(false),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = { name: input.name, isDefault: input.default };
    if (input.description) attributes.description = input.description;
    if (input.icon) attributes.icon = input.icon;
    if (input.displayOrder !== undefined) attributes.displayOrder = input.displayOrder;
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    const response = await ctx.client
      .getAxiosInstance()
      .post<{ data?: { id?: string } }>('/api/ui-menus', {
        data: { type: 'ui-menus', attributes },
      });
    return {
      data: response.data,
      message: `Menu "${input.name}" created`,
      ids: response.data.data?.id ? [response.data.data.id] : [],
    };
  },
});

const menuApply = defineCommand({
  group: 'menus',
  name: 'apply',
  summary: 'Apply a menu tree file (items/groups) in one call, creating the menu if needed',
  options: [
    {
      flag: '--file <path>',
      description: 'Menu tree JSON file — see `menus get <name> --tree` for the shape',
    },
    {
      flag: '--name <name>',
      description:
        'Menu name (overrides the file\'s own "name"; creates the menu if it does not exist yet)',
    },
  ],
  input: z.object({ file: z.string().min(1), name: z.string().optional() }),
  handler: async (ctx, input) => {
    const tree = readDataArgument('@' + input.file);
    const name = input.name ?? (typeof tree.name === 'string' ? tree.name : undefined);
    if (!name) {
      throw new CliError('Menu name required — pass --name or set "name" in the tree file', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const body = { ...tree };
    delete body.name;
    const path = `/api/ui-menus/${encodeURIComponent(name)}/tree`;
    const response = await ctx.client.getAxiosInstance().put<MenuTreeCounts>(path, body);
    const counts = response.data;
    return {
      data: counts,
      message:
        `Menu tree applied to "${name}" ` +
        `(created=${counts.created ?? 0}, updated=${counts.updated ?? 0}, ` +
        `deleted=${counts.deleted ?? 0}, unchanged=${counts.unchanged ?? 0})`,
      ids: counts.menuId ? [counts.menuId] : [],
    };
  },
});

const menuUpdate = defineCommand({
  group: 'menus',
  name: 'update',
  summary: 'Update a UI menu by id',
  positionals: [{ name: 'menuId', description: 'Menu id', required: true }],
  options: [
    { flag: '--name <name>', description: 'Menu name' },
    { flag: '--description <text>', description: 'Description' },
    { flag: '--icon <name>', description: 'Icon rendered beside the app label' },
    { flag: '--display-order <n>', description: 'Ordinal position among sibling menus' },
    { flag: '--default <bool>', description: 'true|false' },
    { flag: '--active <bool>', description: 'true|false' },
    { flag: '--data <json>', description: 'Extra attributes as JSON, @file, or - (merged last)' },
  ],
  input: z.object({
    menuId: z.string().min(1),
    name: z.string().optional(),
    description: z.string().optional(),
    icon: z.string().optional(),
    displayOrder: z.coerce.number().int().optional(),
    default: z.enum(['true', 'false']).optional(),
    active: z.enum(['true', 'false']).optional(),
    data: z.string().optional(),
  }),
  handler: async (ctx, input) => {
    const attributes: Record<string, unknown> = {};
    if (input.name) attributes.name = input.name;
    if (input.description) attributes.description = input.description;
    if (input.icon) attributes.icon = input.icon;
    if (input.displayOrder !== undefined) attributes.displayOrder = input.displayOrder;
    if (input.default !== undefined) attributes.isDefault = input.default === 'true';
    if (input.active !== undefined) attributes.active = input.active === 'true';
    if (input.data) Object.assign(attributes, readDataArgument(input.data));
    if (Object.keys(attributes).length === 0) {
      throw new CliError('Nothing to update — pass at least one field flag', {
        code: 'INVALID_ARGUMENTS',
        exitCode: EXIT.USAGE,
      });
    }
    const response = await ctx.client
      .getAxiosInstance()
      .patch<unknown>(`/api/ui-menus/${input.menuId}`, {
        data: { type: 'ui-menus', id: input.menuId, attributes },
      });
    return { data: response.data, message: `Menu ${input.menuId} updated` };
  },
});

const menuDelete = defineCommand({
  group: 'menus',
  name: 'delete',
  summary: 'Delete a UI menu',
  dangerous: true,
  positionals: [{ name: 'menuId', description: 'Menu id', required: true }],
  input: z.object({ menuId: z.string().min(1) }),
  handler: async (ctx, input) => {
    await ctx.client.getAxiosInstance().delete(`/api/ui-menus/${input.menuId}`);
    return {
      data: { deleted: true, id: input.menuId },
      message: `Menu ${input.menuId} deleted`,
      ids: [input.menuId],
    };
  },
});

export const menuCommands: RegisteredCommand[] = [
  menuList,
  menuGet,
  menuCreate,
  menuApply,
  menuUpdate,
  menuDelete,
];
