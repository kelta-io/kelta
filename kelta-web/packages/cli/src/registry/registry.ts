import { apiCommands } from '../commands/api.js';
import { auditCommands } from '../commands/audit.js';
import { authCommands } from '../commands/auth.js';
import { tokenCommands } from '../commands/token.js';
import { collectionCommands } from '../commands/collections.js';
import { dashboardCommands } from '../commands/dashboards.js';
import { docsCommands } from '../commands/docs.js';
import { constraintCommands } from '../commands/constraints.js';
import { environmentCommands } from '../commands/environments.js';
import { fieldCommands } from '../commands/fields.js';
import { flowCommands } from '../commands/flows.js';
import { layoutCommands } from '../commands/layouts.js';
import { limitCommands } from '../commands/limits.js';
import { manifestCommands } from '../commands/manifest.js';
import { mcpCommands } from '../commands/mcp.js';
import { menuCommands } from '../commands/menus.js';
import { metadataCommands } from '../commands/metadata.js';
import { pageCommands } from '../commands/pages.js';
import { picklistCommands } from '../commands/picklists.js';
import { profileCommands } from '../commands/profile.js';
import { recordCommands } from '../commands/records.js';
import { reportCommands } from '../commands/reports.js';
import { sdkCommands } from '../commands/sdk.js';
import { updateCommands } from '../commands/update.js';
import { userCommands } from '../commands/users.js';
import { validationCommands } from '../commands/validation.js';
import type { RegisteredCommand } from './types.js';

/** Every CLI command. The manifest and local MCP tools derive from this list. */
export const allCommands: RegisteredCommand[] = [
  ...authCommands,
  ...tokenCommands,
  ...profileCommands,
  ...collectionCommands,
  ...fieldCommands,
  ...picklistCommands,
  ...validationCommands,
  ...constraintCommands,
  ...layoutCommands,
  ...pageCommands,
  ...menuCommands,
  ...dashboardCommands,
  ...reportCommands,
  ...flowCommands,
  ...userCommands,
  ...limitCommands,
  ...auditCommands,
  ...recordCommands,
  ...metadataCommands,
  ...environmentCommands,
  ...sdkCommands,
  ...manifestCommands,
  ...updateCommands,
  ...mcpCommands,
  ...apiCommands,
  ...docsCommands,
];
