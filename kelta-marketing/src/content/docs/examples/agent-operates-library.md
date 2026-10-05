---
title: "Example 3: let an AI agent operate the library over MCP"
description: Connect an MCP client to the hosted endpoints, let the agent register a member, lend a book and extend the loan, put a limited volunteer profile next to it, and trace every change in the audit trail and record history.
section: examples
order: 30
---

The lending library from [example 1](/docs/examples/library-app/), with the automation from
[example 2](/docs/examples/automate-library/), is now handed to an AI agent. The agent works through the
[Model Context Protocol](/docs/mcp/overview/): it calls the same API, under the same permissions and validation
rules, as a person would — and it leaves the same trail.

> **Environment.** Every call on this page was made against the hosted **`examples`** demo tenant
> (`app.kelta.io/examples`) with the `kelta` CLI **1.0.1427** (git `b3a8869`) on 2026-10-05; the output blocks are
> what came back, with JSON pretty-printed and wide tables trimmed where noted. The platform does not expose a server
> build endpoint; the cluster's download service reported **1.0.1436** as the newest published release. No real
> token appears on this page. IDs will differ in your tenant.

## 1. Connect the client

Setup — creating a token, choosing a client, verifying — is covered in
[Connecting Claude Code, Claude Desktop, Cursor and others](/docs/mcp/connecting/). For this tenant the two hosted
endpoints are:

| Toolset | URL |
|---|---|
| admin (build and change metadata) | `https://api.kelta.io/examples/mcp/admin` |
| user (work with records) | `https://api.kelta.io/examples/mcp/user` |

Put the token in an environment variable rather than in the configuration file:

```bash
export KELTA_API_KEY=klt_...   # a personal access token — see Personal access tokens
```

**Claude Code**

```bash
claude mcp add --transport http kelta-user \
  https://api.kelta.io/examples/mcp/user \
  --header "Authorization: Bearer $KELTA_API_KEY"
```

**JSON-configured clients** (`.mcp.json` and similar; clients that expand environment variables substitute
`${KELTA_API_KEY}`, otherwise paste the token):

```json
{
  "mcpServers": {
    "kelta-admin": {
      "type": "http",
      "url": "https://api.kelta.io/examples/mcp/admin",
      "headers": { "Authorization": "Bearer ${KELTA_API_KEY}" }
    },
    "kelta-user": {
      "type": "http",
      "url": "https://api.kelta.io/examples/mcp/user",
      "headers": { "Authorization": "Bearer ${KELTA_API_KEY}" }
    }
  }
}
```

The CLI prints the same shape for the profile you are logged in with — tokens left as a placeholder:

```bash
kelta mcp install generic --direct --toolset admin
```

```text
{
  "mcpServers": {
    "kelta-default-admin": {
      "type": "http",
      "url": "https://api.kelta.io/examples/mcp/admin",
      "headers": {
        "Authorization": "Bearer <YOUR_PAT>"
      }
    }
  }
}
# Replace <YOUR_PAT>; tokens come from: kelta token create --name mcp
```

Without a token the endpoint refuses to talk at all:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X POST https://api.kelta.io/examples/mcp/admin \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

```text
401
```

The agent's session below was driven through `kelta mcp serve`, the CLI's stdio bridge
([Local MCP bridge](/docs/cli/mcp-bridge/)), which forwards every record and metadata tool to the hosted endpoints
above. Each step shows the tool call the agent made and the tool result it got back.

## 2. The agent does the desk work

**Check the connection.**

```json
{ "name": "ping", "arguments": {} }
```

```text
pong (user)
```

**Register a new member.**

```json
{ "name": "create_record", "arguments": { "collection": "members",
  "attributes": { "full_name": "Grace Hopper", "email": "grace@example.org" } } }
```

```json
{
  "data": {
    "attributes": {
      "createdAt": "2026-10-05T01:02:26.576891108Z",
      "full_name": "Grace Hopper",
      "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "recordTypeId": null,
      "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "email": "grace@example.org",
      "updatedAt": "2026-10-05T01:02:26.576891108Z"
    },
    "id": "4c846c6f-910a-4ef0-8e37-ae323157711a",
    "type": "members"
  }
}
```

**Lend her *Kindred*.** Before this call the administrator turned on record versioning for loans
(`kelta collections update loans --data '{"trackHistory":true}'`), so every change from here on is kept as a
version — see [Field history and record versions](/docs/data-model/record-history/).

```json
{ "name": "create_record", "arguments": { "collection": "loans",
  "attributes": { "book": "3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a", "member": "4c846c6f-910a-4ef0-8e37-ae323157711a",
                  "loaned_on": "2026-10-05", "due_on": "2026-10-19" } } }
```

```json
{
  "data": {
    "relationships": {
      "book": {
        "data": {
          "type": "books",
          "id": "3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a"
        }
      },
      "member": {
        "data": {
          "type": "members",
          "id": "4c846c6f-910a-4ef0-8e37-ae323157711a"
        }
      }
    },
    "attributes": {
      "createdAt": "2026-10-05T01:03:38.165774889Z",
      "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "recordTypeId": null,
      "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "book": "3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a",
      "member": "4c846c6f-910a-4ef0-8e37-ae323157711a",
      "returned_on": null,
      "due_on": "2026-10-19",
      "loaned_on": "2026-10-05",
      "updatedAt": "2026-10-05T01:03:38.165774889Z"
    },
    "id": "a959d627-094b-4c11-a316-70edd965a7ab",
    "type": "loans"
  }
}
```

The *Sync book status* flow from example 2 picked the new loan up and marked the book `ON_LOAN` — the agent did not
have to know the flow exists.

**Extend the loan by a week.** `update_record` is a PATCH: only the attributes sent change.

```json
{ "name": "update_record", "arguments": { "collection": "loans", "id": "a959d627-094b-4c11-a316-70edd965a7ab",
  "attributes": { "due_on": "2026-10-26" } } }
```

```json
{
  "data": {
    "relationships": {
      "book": {
        "data": {
          "type": "books",
          "id": "3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a"
        }
      },
      "member": {
        "data": {
          "type": "members",
          "id": "4c846c6f-910a-4ef0-8e37-ae323157711a"
        }
      }
    },
    "attributes": {
      "createdAt": "2026-10-05T01:03:38.165775Z",
      "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "recordTypeId": null,
      "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
      "book": "3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a",
      "member": "4c846c6f-910a-4ef0-8e37-ae323157711a",
      "returned_on": null,
      "due_on": "2026-10-26T00:00:00.000Z",
      "updatedAt": "2026-10-05T01:03:50.862959Z",
      "loaned_on": "2026-10-05T00:00:00.000Z"
    },
    "id": "a959d627-094b-4c11-a316-70edd965a7ab",
    "type": "loans"
  }
}
```

Validation rules apply to agents exactly as to people. Note the
[known issue in example 1](/docs/examples/library-app/#3-a-validation-rule-that-compares-two-fields): a PATCH that
sends only `due_on` is not checked against `loaned_on`, so an agent that moves a due date should send both.

## 3. Draw the boundary: a volunteer profile

An agent acts with the permissions of the user whose token it holds. The token used above belongs to an
administrator, which is right for building the app and wrong for running the desk. The desk role should be able to
record loans and nothing else. Create a profile for it, then a user — run as an administrator, with the CLI:

```bash
kelta records create profiles --data '{"name":"Library Volunteer","description":"Can record loans; cannot change the catalogue"}'
```

```json
{
  "id": "94688ab9-c366-4600-b8fe-c6d7f8be1b07",
  "isSystem": false,
  "createdAt": "2026-10-05T01:00:16.877308806Z",
  "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "name": "Library Volunteer",
  "tenantId": "5770f937-2fd9-4c7c-ac79-f3a447a82c34",
  "description": "Can record loans; cannot change the catalogue",
  "updatedAt": "2026-10-05T01:00:16.877308806Z"
}
```

Grant `API_ACCESS` (so the token works at all), read-only access to `books` and `members`, and create/read/edit on
`loans` ([Profiles and permissions](/docs/security/profiles-and-permissions/)):

```bash
P=94688ab9-c366-4600-b8fe-c6d7f8be1b07
kelta records create profile-system-permissions --quiet \
  --data '{"profileId":"'$P'","permissionName":"API_ACCESS","granted":true}'
kelta records create profile-object-permissions --quiet \
  --data '{"profileId":"'$P'","collectionId":"2cf0aab2-2311-4746-b890-dfedf5984418","canCreate":false,"canRead":true,"canEdit":false,"canDelete":false,"canViewAll":false,"canModifyAll":false}'
kelta records create profile-object-permissions --quiet \
  --data '{"profileId":"'$P'","collectionId":"842948da-0838-4633-907a-118376d136c9","canCreate":false,"canRead":true,"canEdit":false,"canDelete":false,"canViewAll":false,"canModifyAll":false}'
kelta records create profile-object-permissions --quiet \
  --data '{"profileId":"'$P'","collectionId":"52da9a1c-dc69-49a1-ada5-a30829e370af","canCreate":true,"canRead":true,"canEdit":true,"canDelete":false,"canViewAll":false,"canModifyAll":false}'
```

```text
dc8967cf-d60e-4f5e-acd2-8235b98444b1
e9ba23e7-4ebd-440e-8d8b-0f13eacad0b3
c5791289-b7aa-4c57-9112-c4c2bab8c792
ae1cd3d9-46e5-459d-9a50-8285a941116c
```

(The collection ids are `books`, `members` and `loans` from example 1.) Then the user:

```bash
kelta records create users --data '{"email":"volunteer@example.org","firstName":"Sam","lastName":"Volunteer","profileId":"94688ab9-c366-4600-b8fe-c6d7f8be1b07","status":"ACTIVE","userType":"INTERNAL"}'
```

```json
{
  "id": "849f1ddb-8e28-44f6-921d-b06bf5e0ef55",
  "lastName": "Volunteer",
  "settings": {},
  "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "timezone": "UTC",
  "managerId": null,
  "locale": "en_US",
  "loginCount": 0,
  "createdAt": "2026-10-05T01:00:25.752640571Z",
  "firstName": "Sam",
  "lastLoginAt": null,
  "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "profileId": "94688ab9-c366-4600-b8fe-c6d7f8be1b07",
  "tenantId": "5770f937-2fd9-4c7c-ac79-f3a447a82c34",
  "mfaEnabled": false,
  "userType": "INTERNAL",
  "email": "volunteer@example.org",
  "updatedAt": "2026-10-05T01:00:25.752640571Z",
  "status": "ACTIVE",
  "username": null
}
```

An agent configured with a token belonging to Sam can register loans and read the catalogue. If it tries to change
a book — `update_record` on `books`, or `kelta records update books <id> …` — the write is checked against the
*Library Volunteer* profile, which has `canEdit: false` on `books`, and refused before it reaches the data
([how access is evaluated](/docs/security/profiles-and-permissions/#how-access-is-evaluated)); the refusal comes back
in the [error envelope](/docs/api/errors/), and an MCP client sees it as a tool error.

> **Not captured here.** The walkthrough's automation is deliberately not allowed to mint or hold a token for
> any user other than the tenant administrator, so this page does not show Sam's refused write or its audit entry.
> To see it yourself, sign in as a limited user, create a token under *Profile → API tokens*, point the client
> configuration above at it and ask the agent to mark a book `LOST`.

## 4. Follow the trail

Everything above left a record.

**Setup audit trail** — the profile, its permissions and the user (`kelta audit setup` shows the same entries with
their before/after values; this query trims them to the essentials):

```bash
kelta records list setup-audit-entries \
  --filter timestamp.gte=2026-10-05T01:00:00Z --filter timestamp.lt=2026-10-05T01:00:30Z \
  --sort timestamp --fields timestamp,action,section,entityType,entityName,userId --output table
```

```text
timestamp                    action   section   entityType          entityName             userId
---------------------------  -------  --------  ------------------  ---------------------  ------------------------------------
2026-10-05T01:00:16.888321Z  CREATED  Profiles  profile             Library Volunteer      13de168a-ab36-4ec0-a811-647e447f1827
2026-10-05T01:00:24.635039Z  CREATED  Profiles  system-permissions                         13de168a-ab36-4ec0-a811-647e447f1827
2026-10-05T01:00:24.927883Z  CREATED  Profiles  object-permissions                         13de168a-ab36-4ec0-a811-647e447f1827
2026-10-05T01:00:25.211287Z  CREATED  Profiles  object-permissions                         13de168a-ab36-4ec0-a811-647e447f1827
2026-10-05T01:00:25.482670Z  CREATED  Profiles  object-permissions                         13de168a-ab36-4ec0-a811-647e447f1827
2026-10-05T01:00:25.771011Z  CREATED  Users     user                volunteer@example.org  13de168a-ab36-4ec0-a811-647e447f1827
```

(Table trimmed: the `id`, `createdAt`, `updatedBy`, `createdBy` and `updatedAt` columns are removed.)

**Record history** — the agent's create and update of the loan:

```bash
kelta records list record-versions --filter recordId=a959d627-094b-4c11-a316-70edd965a7ab \
  --sort versionNumber --output table
```

```text
versionNumber  changeType  changedFields                           changeSource  changedBy                             changedAt
-------------  ----------  --------------------------------------  ------------  ------------------------------------  ---------------------------
1              CREATED     ["book","member","loaned_on","due_on"]  UI            13de168a-ab36-4ec0-a811-647e447f1827  2026-10-05T01:03:38.186491Z
2              UPDATED     ["due_on"]                              UI            13de168a-ab36-4ec0-a811-647e447f1827  2026-10-05T01:03:50.872474Z
```

(Table trimmed to six of its columns; the full rows also carry a `snapshot` of the record after each change.)
`changedBy` is the token's owner. `changeSource` reads `UI` on this release even though both writes came through
MCP, so do not rely on it to tell agent writes from human ones; use a dedicated user — and token — per agent
instead.

**Flow runs** — the loan the agent created started *Sync book status*; it is the last row of
`kelta records list flow-executions --filter flowId=ba4ffdbc-55df-445b-98c3-3e2858f97fb0 --sort startedAt …`
(same columns as in [example 2](/docs/examples/automate-library/#4-read-the-run-history)):

```text
7e32f5ac-7c25-4fea-9adb-b663d4719d76  a959d627-094b-4c11-a316-70edd965a7ab  MarkOnLoan                                                 2026-10-05T01:03:38.255974Z  COMPLETED
```

## Where next

- [MCP tools](/docs/mcp/tools/) — every tool in the admin and user toolsets
- [Personal access tokens](/docs/security/personal-access-tokens/) — expiry, revocation, minting on a service user's
  behalf
- [Audit logs](/docs/security/audit-log/) — the security audit log, setup audit trail and login history
