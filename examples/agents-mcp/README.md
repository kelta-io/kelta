# An agent builds a task tracker over MCP

An AI agent connects to Kelta's MCP server, reads the tenant's API, builds a small task tracker
from nothing and puts an automation on it: a `tasks` collection with a status picklist, a few
records, a filtered query, and a flow that stamps `completed_at` when a task is moved to `done`.
Every step is a tool call the agent makes; nothing is clicked in the UI.

This is a walkthrough, not an installable template. For a longer story, in which an agent runs a
lending library's front desk, adds a volunteer profile and traces its changes in the audit trail,
see [Let an AI agent operate the library over MCP](https://www.kelta.io/docs/examples/agent-operates-library/)
(`/docs/examples/agent-operates-library/`).

> **Run.** The calls and responses below were recorded on 2026-10-16 against the hosted `examples`
> demo tenant, through the `kelta` CLI's stdio bridge (`kelta mcp serve --source remote`, CLI
> 1.0.1585), which forwards each call to the hosted admin and user endpoints. Long responses are
> trimmed where marked and JSON is pretty-printed. IDs, timestamps and user ids will differ in your
> tenant.

## What you need

- A tenant you can create collections and flows in, and a personal access token (`klt_...`) for a
  user with that permission. Create one under **Profile → API tokens** in the app, or with
  `kelta token create --name mcp`.
- An MCP client: Claude Code, Claude Desktop, Cursor, or any client that speaks MCP over HTTP.

## 1. Connect an MCP client to the admin and user toolsets

Kelta serves two toolsets per tenant:

| Toolset | URL | Used here for |
|---|---|---|
| admin | `https://api.kelta.io/<tenant>/mcp/admin` | picklists, collections, flows |
| user | `https://api.kelta.io/<tenant>/mcp/user` | `describe_api`, records, flow runs |

Keep the token in an environment variable, never in a file you commit:

```bash
export KELTA_API_KEY=klt_...
```

**Claude Code**

```bash
claude mcp add --transport http kelta-admin https://api.kelta.io/<tenant>/mcp/admin \
  --header "Authorization: Bearer $KELTA_API_KEY"
claude mcp add --transport http kelta-user https://api.kelta.io/<tenant>/mcp/user \
  --header "Authorization: Bearer $KELTA_API_KEY"
```

**JSON-configured clients** (`.mcp.json` and similar; paste the token if your client does not
expand `${KELTA_API_KEY}`):

```json
{
  "mcpServers": {
    "kelta-admin": {
      "type": "http",
      "url": "https://api.kelta.io/<tenant>/mcp/admin",
      "headers": { "Authorization": "Bearer ${KELTA_API_KEY}" }
    },
    "kelta-user": {
      "type": "http",
      "url": "https://api.kelta.io/<tenant>/mcp/user",
      "headers": { "Authorization": "Bearer ${KELTA_API_KEY}" }
    }
  }
}
```

**Through the CLI.** If you are signed in with the `kelta` CLI, `kelta mcp install generic --direct`
prints the same configuration for your profile (with `<YOUR_PAT>` in place of the token), and
`kelta mcp serve --toolset all` runs a stdio server that bridges both toolsets with your profile's
token. This walkthrough was recorded through that bridge.

**Local stack (`docker compose up`).** The compose stack (see the
[Docker Compose deployment](https://www.kelta.io/docs/deploy/docker-compose/) page) does not
include `kelta-mcp`. Run it next to the stack from a checkout of this repository, pointed at the
gateway on `localhost:8080`; it listens on `localhost:8090`:

```bash
mvn clean install -DskipTests -B -f kelta-platform/pom.xml \
  -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-jsonapi -am
SERVER_PORT=8090 GATEWAY_URL=http://localhost:8080 mvn spring-boot:run -f kelta-mcp/pom.xml
```

Then use `http://localhost:8090/default/mcp/admin` and `http://localhost:8090/default/mcp/user`
(the compose stack's tenant is `default`) in place of the hosted URLs above, with a token created
in the local app. Every call below works the same way.

**Check the connection.**

```json
{ "name": "ping", "arguments": {} }
```

```text
pong (user)
```

## 2. Read the API: `describe_api`

The agent starts by reading the tenant's OpenAPI spec, which lists a JSON:API path for every
collection the tenant already has. There is no `/api/tasks` path yet.

```json
{ "name": "describe_api", "arguments": {} }
```

Response (trimmed: the full spec was 1.1 MB with 239 paths; your tenant's paths will differ):

```text
{"openapi":"3.0.3","info":{"version":"1.0.0","description":"Auto-generated API documentation from collection schemas","title":"Kelta Platform API"},"components":{"securitySchemes":{"bearerAuth":{"bearerFormat":"JWT","scheme":"bearer","type":"http"}},"schemas":{ … }},"paths":{ … "/api/collections": { … }, … "/api/fields": { … }, … }}
```

For one collection's fields, `get_collection_schema` is cheaper than parsing the whole spec; the
agent uses it in the next step.

## 3. Create the `tasks` collection with a status picklist: `create_collection`

A picklist field needs a picklist to draw its values from, so the agent creates that first with
`create_picklist` (admin toolset):

```json
{ "name": "create_picklist", "arguments": {
  "name": "task-status",
  "description": "Where a task is in its life cycle",
  "values": [
    { "value": "todo",  "label": "To do" },
    { "value": "doing", "label": "Doing" },
    { "value": "done",  "label": "Done" }
  ] } }
```

```text
{
  "picklist": {"data":{"relationships":{"updatedBy":{"data":{"type":"users","id":"13de168a-ab36-4ec0-a811-647e447f1827"}},"createdBy":{"data":{"type":"users","id":"13de168a-ab36-4ec0-a811-647e447f1827"}}},"attributes":{"createdAt":"2026-10-16T18:25:11.106053577Z","sorted":false,"updatedBy":"13de168a-ab36-4ec0-a811-647e447f1827","createdBy":"13de168a-ab36-4ec0-a811-647e447f1827","restricted":true,"name":"task-status","tenantId":"5770f937-2fd9-4c7c-ac79-f3a447a82c34","description":"Where a task is in its life cycle","updatedAt":"2026-10-16T18:25:11.106053577Z"},"id":"8e30dbad-a97d-4fdd-96a0-67ca2e257862","type":"global-picklists"}},
  "values": [{value=todo, index=0, status=201, body=ok}, {value=doing, index=1, status=201, body=ok}, {value=done, index=2, status=201, body=ok}]
}
```

Then `create_collection` creates `tasks` with its three fields in one call. The `status` field
points at the picklist by the `id` returned above (`picklistSourceId`) and defaults to `todo`;
`completed_at` is a datetime the flow will fill in.

```json
{ "name": "create_collection", "arguments": {
  "name": "tasks",
  "displayName": "Tasks",
  "displayFieldName": "title",
  "description": "A small task tracker built by an agent over MCP",
  "fields": [
    { "fieldName": "title", "displayName": "Title", "type": "text", "required": true },
    { "fieldName": "status", "displayName": "Status", "type": "picklist",
      "picklistSourceId": "8e30dbad-a97d-4fdd-96a0-67ca2e257862", "defaultValue": "todo" },
    { "fieldName": "completed_at", "displayName": "Completed at", "type": "datetime" }
  ] } }
```

```text
{
  "collection": {"data":{"relationships":{"updatedBy":{"data":{"type":"users","id":"13de168a-ab36-4ec0-a811-647e447f1827"}},"createdBy":{"data":{"type":"users","id":"13de168a-ab36-4ec0-a811-647e447f1827"}},"displayFieldId":{"data":null}},"attributes":{"updatedBy":"13de168a-ab36-4ec0-a811-647e447f1827","displayFieldId":null,"displayName":"Tasks","ownerScope":"NONE","adapterConfig":null,"description":"A small task tracker built by an agent over MCP","active":true,"captureGeo":false,"currentVersion":1,"createdAt":"2026-10-16T18:25:32.914811851Z","path":"/api/tasks","createdBy":"13de168a-ab36-4ec0-a811-647e447f1827","ownerField":null,"systemCollection":false,"name":"tasks","tenantId":"5770f937-2fd9-4c7c-ac79-f3a447a82c34","ownerScopeReads":true,"trackHistory":false,"updatedAt":"2026-10-16T18:25:32.914811851Z"},"id":"28d36f6b-106a-4887-a2f7-2835fa3901dd","type":"collections"}},
  "fields": [{index=0, status=201, body=ok, fieldName=title}, {index=1, status=201, body=ok, fieldName=status}, {index=2, status=201, body=ok, fieldName=completed_at}]
}
```

Each field reports its own HTTP status, so the agent can see that all three were created. It
confirms the shape with `get_collection_schema` (user toolset):

```json
{ "name": "get_collection_schema", "arguments": { "collection": "tasks" } }
```

Response (trimmed to the three fields the agent created; the schema also lists the system fields
`created_at`, `created_by`, `updated_at` and `updated_by`):

```json
{
  "name": "tasks",
  "displayName": "Tasks",
  "systemCollection": false,
  "fields": [
    { "name": "title", "type": "STRING", "required": true, "isRelationship": false, "description": null, "systemMaintained": false },
    { "name": "status", "type": "PICKLIST", "required": false, "isRelationship": false, "description": null, "systemMaintained": false, "default": "todo" },
    { "name": "completed_at", "type": "DATETIME", "required": false, "isRelationship": false, "description": null, "systemMaintained": false },
    …
  ],
  "validationRules": []
}
```

## 4. Add tasks and query them: `create_record` and `query_collection`

Three tasks, one left at the default status:

```json
{ "name": "create_record", "arguments": { "collection": "tasks",
  "attributes": { "title": "Write the onboarding guide" } } }
```

```json
{
  "data": {
    "attributes": {
      "createdAt": "2026-10-16T18:26:11.862780502Z",
      "completed_at": null,
      "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "recordTypeId": null,
      "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "title": "Write the onboarding guide",
      "updatedAt": "2026-10-16T18:26:11.862780502Z",
      "status": "todo"
    },
    "id": "313c10dc-8222-401a-a632-97429b839b2d",
    "type": "tasks"
  }
}
```

```json
{ "name": "create_record", "arguments": { "collection": "tasks",
  "attributes": { "title": "Review the pull request", "status": "doing" } } }
```

```json
{
  "data": {
    "attributes": {
      "createdAt": "2026-10-16T18:26:19.786249751Z",
      "completed_at": null,
      "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "recordTypeId": null,
      "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "title": "Review the pull request",
      "status": "doing",
      "updatedAt": "2026-10-16T18:26:19.786249751Z"
    },
    "id": "882f1e8e-cd8a-4edd-9a68-cf81597178f0",
    "type": "tasks"
  }
}
```

```json
{ "name": "create_record", "arguments": { "collection": "tasks",
  "attributes": { "title": "Book the team retro", "status": "doing" } } }
```

```json
{
  "data": {
    "attributes": {
      "createdAt": "2026-10-16T18:26:27.787606652Z",
      "completed_at": null,
      "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "recordTypeId": null,
      "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "title": "Book the team retro",
      "status": "doing",
      "updatedAt": "2026-10-16T18:26:27.787606652Z"
    },
    "id": "a9ccebc9-4098-4f46-9c7f-5f207d957533",
    "type": "tasks"
  }
}
```

What is in progress? A filter is `{ field: { OP: value } }`; the operators are listed in the
tool's `filter` argument (`EQ`, `NEQ`, `IN`, `GTE`, …).

```json
{ "name": "query_collection", "arguments": { "collection": "tasks",
  "filter": { "status": { "EQ": "doing" } },
  "sort": "title",
  "fields": "title,status,completed_at" } }
```

Response (the run returned every attribute despite the `fields` list):

```json
{
  "metadata": { "totalCount": 2, "currentPage": 1, "pageSize": 20, "totalPages": 1 },
  "data": [
    {
      "attributes": {
        "createdAt": "2026-10-16T18:26:27.787607Z",
        "completed_at": null,
        "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
        "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
        "title": "Book the team retro",
        "updatedAt": "2026-10-16T18:26:27.787607Z",
        "status": "doing"
      },
      "id": "a9ccebc9-4098-4f46-9c7f-5f207d957533",
      "type": "tasks"
    },
    {
      "attributes": {
        "createdAt": "2026-10-16T18:26:19.786250Z",
        "completed_at": null,
        "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
        "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
        "title": "Review the pull request",
        "updatedAt": "2026-10-16T18:26:19.786250Z",
        "status": "doing"
      },
      "id": "882f1e8e-cd8a-4edd-9a68-cf81597178f0",
      "type": "tasks"
    }
  ],
  "meta": { "totalCount": 2, "currentPage": 1, "pageSize": 20, "totalPages": 1 },
  "links": {
    "self": "/api/tasks?filter%5Bstatus%5D%5BEQ%5D=doing&sort=title&fields=title%2Cstatus%2Ccompleted_at&page[number]=1&page[size]=20",
    "prev": null,
    "next": null
  }
}
```

## 5. Stamp finished tasks: `create_flow`

The arguments for this call are committed as
[`stamp-completed-tasks.json`](stamp-completed-tasks.json); pass the file's content as the
`arguments` of `create_flow` (admin toolset). The flow is `RECORD_TRIGGERED` on `tasks`, starts
only when `status` changed (`triggerFields`), and, when the new status is `done`, writes the
record's `updatedAt` into `completed_at` with an `UPDATE_RECORD` task. Because only `status` is a
trigger field, the flow's own write to `completed_at` cannot start it again. The flow reference is
in the MCP server's authoring docs ([`flows.md`](../../kelta-mcp/src/main/resources/docs/authoring/flows.md));
the [CRM template](../templates/crm/README.md) carries a larger stamp flow.

```json
{ "name": "create_flow", "arguments": {
  "name": "Stamp completed tasks",
  "description": "Sets completed_at when a task's status changes to done",
  "flowType": "RECORD_TRIGGERED",
  "active": true,
  "triggerConfig": { "collection": "tasks", "events": ["UPDATED"], "triggerFields": ["status"] },
  "definition": {
    "StartAt": "IsDone",
    "States": {
      "IsDone": {
        "Type": "Choice",
        "Choices": [ { "Variable": "$.record.data.status", "StringEquals": "done", "Next": "StampCompletedAt" } ],
        "Default": "NotDone"
      },
      "StampCompletedAt": {
        "Type": "Task",
        "Resource": "UPDATE_RECORD",
        "Parameters": {
          "targetCollectionName": "tasks",
          "recordId": "${$.record.data.id}",
          "updates": [ { "field": "completed_at", "value": "${$.record.data.updatedAt}" } ]
        },
        "ResultPath": "$.stamp",
        "End": true
      },
      "NotDone": { "Type": "Succeed" }
    }
  } } }
```

Response (trimmed: `definition` echoes the one sent):

```json
{
  "data": {
    "attributes": {
      "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "triggerConfig": { "collection": "tasks", "events": ["UPDATED"], "triggerFields": ["status"] },
      "description": "Sets completed_at when a task's status changes to done",
      "active": true,
      "version": 1,
      "createdAt": "2026-10-16T18:26:54.296030931Z",
      "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "stepLogging": "FULL",
      "retentionDays": null,
      "name": "Stamp completed tasks",
      "tenantId": "5770f937-2fd9-4c7c-ac79-f3a447a82c34",
      "definition": { "StartAt": "IsDone", "States": { … } },
      "runAsUserId": null,
      "flowType": "RECORD_TRIGGERED",
      "updatedAt": "2026-10-16T18:26:54.296030931Z"
    },
    "id": "3099c95e-3d5d-4ece-8ef9-56abb977a03d",
    "type": "flows"
  }
}
```

## 6. Finish a task and see the stamp: `update_record` and `get_record`

```json
{ "name": "update_record", "arguments": { "collection": "tasks",
  "id": "882f1e8e-cd8a-4edd-9a68-cf81597178f0",
  "attributes": { "status": "done" } } }
```

The update returns the record as it was saved; the flow has not run yet, so `completed_at` is
still empty:

```json
{
  "data": {
    "attributes": {
      "createdAt": "2026-10-16T18:26:19.786250Z",
      "completed_at": null,
      "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "recordTypeId": null,
      "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "title": "Review the pull request",
      "updatedAt": "2026-10-16T18:27:21.638777Z",
      "status": "done"
    },
    "id": "882f1e8e-cd8a-4edd-9a68-cf81597178f0",
    "type": "tasks"
  }
}
```

A moment later (the flow runs asynchronously on the record-change event), the agent reads the
record back:

```json
{ "name": "get_record", "arguments": { "collection": "tasks",
  "id": "882f1e8e-cd8a-4edd-9a68-cf81597178f0" } }
```

```json
{
  "data": {
    "attributes": {
      "createdAt": "2026-10-16T18:26:19.786250Z",
      "completed_at": "2026-10-16T18:27:21.638777Z",
      "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "recordTypeId": null,
      "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "title": "Review the pull request",
      "updatedAt": "2026-10-16T18:27:22.067409Z",
      "status": "done"
    },
    "id": "882f1e8e-cd8a-4edd-9a68-cf81597178f0",
    "type": "tasks"
  }
}
```

`completed_at` now holds the time the task was moved to `done`, and `updatedAt` moved on by the
flow's own write.

**Optional: inspect the run.** `get_flow_run` takes an execution id. The MCP toolsets have no tool
that lists a flow's runs; get the id from `kelta flows runs <flowId>` or the flow's run history in
the app.

```json
{ "name": "get_flow_run", "arguments": { "executionId": "dce93ac6-3a0f-4b4d-be63-06dc8a5c2a85" } }
```

Response (trimmed to the run summary and the stamp step's result; `stateData` and `initialInput`
carry the whole state envelope):

```json
{
  "data": {
    "type": "flow-executions",
    "id": "dce93ac6-3a0f-4b4d-be63-06dc8a5c2a85",
    "attributes": {
      "flowId": "3099c95e-3d5d-4ece-8ef9-56abb977a03d",
      "status": "COMPLETED",
      "triggerRecordId": "882f1e8e-cd8a-4edd-9a68-cf81597178f0",
      "currentNodeId": "StampCompletedAt",
      "stepCount": 2,
      "durationMs": 18,
      "errorMessage": null,
      "stateData": {
        "stamp": {
          "updatedFields": {
            "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
            "completed_at": "2026-10-16T18:27:21.638776990Z"
          },
          "targetRecordId": "882f1e8e-cd8a-4edd-9a68-cf81597178f0",
          "targetCollectionName": "tasks",
          …
        },
        …
      },
      …
    }
  }
}
```

## 7. Clean up

The same toolsets remove what the agent built: `delete_record` for each task,
`delete_collection` with `{ "collection": "tasks" }` (drops the collection and any records left in
it) and `delete_picklist` with `{ "picklist": "task-status" }`.

A flow that has run cannot be deleted: `delete_flow` answers `409 REFERENCED_RECORD` while its
runs exist. Deactivate it instead with `update_flow` and `{ "id": "<flowId>", "active": false }`.
Adding `"retentionDays": 1` to that call shortens how long its runs are kept, where the operator
has the run-history sweep turned on; once they are gone the flow can be deleted.
