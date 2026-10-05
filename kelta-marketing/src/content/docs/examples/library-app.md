---
title: "Example 1: a lending library, built from the CLI"
description: Build a small lending-library app — three collections, a global picklist, a cross-field validation rule, a saved list view, a custom page and an app menu — with nothing but kelta CLI commands, and see the real output of each step.
section: examples
order: 10
---

This is the first of three worked examples that build on one app. Here you build it; in
[example 2](/docs/examples/automate-library/) you automate it; in [example 3](/docs/examples/agent-operates-library/)
an AI agent operates it.

> **Environment.** Every command on this page was run against the hosted **`examples`** demo tenant
> (`app.kelta.io/examples`) with the `kelta` CLI **1.0.1427** (`kelta version` → git `b3a8869`) on 2026-10-05; the
> output blocks are what came back, unedited except where a block says it was trimmed. The platform does not expose a
> server build endpoint; the cluster's download service reported **1.0.1436** as the newest published release
> (`kelta update --check`). IDs will differ in your tenant.

If you have not built anything on Kelta yet, start with [Tutorial: your first app](/docs/getting-started/first-app/):
it builds an Invoices app three ways (console, CLI, MCP) and explains what each object is. This example skips the
explanations and goes further — a validation rule that compares two fields, and a custom page — using only the CLI.

## What you will build

| Object | Purpose |
|---|---|
| `book-status` global picklist | `AVAILABLE`, `ON_LOAN`, `LOST` |
| `members` collection | `full_name`, `email` (unique) |
| `books` collection | `title`, `author`, `isbn` (unique), `status` (picklist, default `AVAILABLE`) |
| `loans` collection | `book` and `member` (references), `loaned_on`, `due_on`, `returned_on` (dates) |
| *Due after loan date* validation rule | rejects a loan whose `due_on` is not after `loaned_on` |
| *Open loans* list view | public, loans with no `returned_on`, soonest due first |
| `/library-desk` page | a heading, a count of books on loan and a table of loans |
| *Library* menu | an app with Desk, Loans, Books and Members tabs |

You need the CLI installed and logged in as a user with `CUSTOMIZE_APPLICATION`
([Install the CLI](/docs/getting-started/install-cli/), [Profiles and logins](/docs/cli/auth-and-profiles/)).

## 1. A picklist and two simple collections

```bash
kelta picklists create --name book-status --values AVAILABLE,ON_LOAN,LOST
```

```json
{
  "id": "689d409d-0f78-49dc-a22c-e158f6a06d83",
  "createdAt": "2026-10-05T00:56:57.904613273Z",
  "sorted": false,
  "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "restricted": true,
  "name": "book-status",
  "tenantId": "5770f937-2fd9-4c7c-ac79-f3a447a82c34",
  "description": null,
  "updatedAt": "2026-10-05T00:56:57.904613273Z"
}
```

Create `members` and `books` and their fields. Run with `--output table`, each command prints the new object as a
wide table followed by a confirmation line; the confirmation lines (tables trimmed) were:

```bash
kelta collections create --name members --display-name Members
kelta fields add members --name full_name --type text --display-name "Full name" --required
kelta fields add members --name email --type email --required --unique

kelta collections create --name books --display-name Books
kelta fields add books --name title  --type text --required
kelta fields add books --name author --type text
kelta fields add books --name isbn   --type text --display-name ISBN --unique
kelta fields add books --name status --type picklist --picklist book-status --default AVAILABLE
```

```text
Collection "members" created (id 842948da-0838-4633-907a-118376d136c9)
Field "full_name" (STRING) added to members
Field "email" (EMAIL) added to members
Collection "books" created (id 2cf0aab2-2311-4746-b890-dfedf5984418)
Field "title" (STRING) added to books
Field "author" (STRING) added to books
Field "isbn" (STRING) added to books
Field "status" (PICKLIST) added to books
```

The picklist field records its source in `fieldTypeConfig`:
`{"picklistSourceType":"GLOBAL","globalPicklistId":"689d409d-0f78-49dc-a22c-e158f6a06d83"}`.

## 2. The loans collection, with references

`--quiet` prints only the new id, which is what a script wants:

```bash
kelta collections create --name loans --display-name Loans --quiet
kelta fields add loans --name book   --type reference --reference books   --required --quiet
kelta fields add loans --name member --type reference --reference members --required --quiet
kelta fields add loans --name loaned_on   --type date --display-name "Loaned on"   --required --quiet
kelta fields add loans --name due_on      --type date --display-name "Due on"      --required --quiet
kelta fields add loans --name returned_on --type date --display-name "Returned on" --quiet
```

```text
52da9a1c-dc69-49a1-ada5-a30829e370af
add96104-5900-4409-8320-d386e92fe69b
618f0690-963a-416d-8c3b-554fe75bef44
1ee48e49-8c55-46cf-9b3e-90f530aadfe6
bc7dc0ac-221e-4f45-8647-cab97b0361c7
5f596a1e-4800-46cd-a0fa-0d9ef53cee78
```

A reference value must point at an existing record; see [Relationships](/docs/data-model/relationships/).

## 3. A validation rule that compares two fields

The formula describes the **error**: when it is `TRUE`, the write is rejected.

```bash
kelta validation-rules create loans \
  --name "Due after loan date" \
  --formula "due_on <= loaned_on" \
  --message "The due date must be after the loan date" \
  --error-field due_on
```

```text
Validation rule "Due after loan date" created on loans
```

Seed a member and two books, then try a loan that is due before it starts:

```bash
kelta records create members --data '{"full_name":"Ada Byron","email":"ada@example.org"}'
kelta records create books --data '{"title":"The Left Hand of Darkness","author":"Ursula K. Le Guin","isbn":"9780441478125"}'
kelta records create books --data '{"title":"Kindred","author":"Octavia E. Butler","isbn":"9780807083697"}'
```

```json
{
  "id": "3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a",
  "createdAt": "2026-10-05T00:57:28.230909517Z",
  "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "recordTypeId": null,
  "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "author": "Octavia E. Butler",
  "isbn": "9780807083697",
  "title": "Kindred",
  "updatedAt": "2026-10-05T00:57:28.230909517Z",
  "status": "AVAILABLE"
}
```

(That is the output of the last command; `status` took the field default.) The member came back as
`dad55720-dece-4e0c-9149-0c5c51961de0`.

```bash
kelta records create loans --data '{"book":"3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a","member":"dad55720-dece-4e0c-9149-0c5c51961de0","loaned_on":"2026-10-05","due_on":"2026-10-01"}'
```

```json
{"error":{"code":"VALIDATION_RULE_FAILED","status":422,"detail":"The due date must be after the loan date","source":{"pointer":"/data/attributes/due_on"},"meta":{"requestId":"63c0842d"}},"errors":[{"status":"422","code":"VALIDATION_RULE_FAILED","title":"Validation Error","detail":"The due date must be after the loan date","source":{"pointer":"/data/attributes/due_on"},"meta":{"requestId":"63c0842d"}}]}
```

The command exits `1`. Branch on `code` (`VALIDATION_RULE_FAILED`), and use `source.pointer` to put the message
next to the field.

> **Known issue (observed 2026-10-05).** The rule is evaluated against the attributes in the request. A partial update that sends
> only `due_on` — `kelta records update loans <id> --data '{"due_on":"2026-10-01"}'` — was accepted on the
> `examples` tenant; the same update sending both `loaned_on` and `due_on` was rejected as above. Until that is
> fixed, send both fields when you change either.

## 4. A saved list view

```bash
kelta list-views apply loans \
  --name "Open loans" \
  --columns book,member,loaned_on,due_on \
  --filter returned_on.isnull=true \
  --sort due_on \
  --visibility PUBLIC --default true
```

```json
{
  "action": "created",
  "id": "2922d657-0b9f-4bb2-8c81-e7f227c3eee7"
}
```

`apply` is keyed on collection + name, so it is safe to keep in a setup script. See
[List views](/docs/console/list-views/) and the [list view authoring reference](/docs/reference/list-views/).

## 5. A custom page

Save the page as `library-desk.json`. Everything about the page lives under `config`
([UI pages reference](/docs/reference/ui-pages/)):

```json
{
  "name": "library-desk",
  "path": "/library-desk",
  "title": "Library desk",
  "config": {
    "schemaVersion": 2,
    "dataSources": [
      { "name": "onLoan", "collection": "books", "mode": "list", "filter": { "status": "ON_LOAN" }, "limit": 50 },
      { "name": "loans", "collection": "loans", "mode": "list", "sort": "due_on", "limit": 50 }
    ],
    "components": [
      { "id": "h1", "type": "heading", "props": { "text": "Library desk", "level": "h2" } },
      { "id": "t0", "type": "text", "props": { "text": "{{ data.onLoan.length }} books are out on loan." } },
      { "id": "t1", "type": "table", "props": { "source": { "$bind": "data.loans" } } }
    ]
  }
}
```

Validate it first, then create it. `pages apply` asks for confirmation, so a script passes `--yes`:

```bash
kelta pages apply library-desk.json --dry-run
kelta pages apply library-desk.json --yes
kelta pages publish /library-desk
```

```json
{
  "valid": true,
  "errors": []
}
{
  "action": "created",
  "id": "e7bd3df9-9bf6-417e-9813-cf7303c27147",
  "path": "/library-desk",
  "published": false
}
{
  "action": "published",
  "id": "e7bd3df9-9bf6-417e-9813-cf7303c27147",
  "path": "/library-desk"
}
```

Without `--yes` the apply stops with
`{"error":{"code":"CONFIRMATION_REQUIRED","detail":"\"pages apply\" is destructive — pass --yes to proceed"}}` and
exit code `2`.

## 6. An app menu

Save as `library-menu.json`:

```json
{
  "name": "Library",
  "items": [
    { "label": "Desk", "path": "/p/library-desk" },
    { "label": "Loans", "path": "/resources/loans" },
    { "label": "Books", "path": "/resources/books" },
    { "label": "Members", "path": "/resources/members" }
  ]
}
```

```bash
kelta menus apply --file library-menu.json
```

```json
{
  "menuId": "280e295f-9422-4673-b884-ea3566f0fef2",
  "name": "Library",
  "created": 5,
  "updated": 0,
  "deleted": 0,
  "unchanged": 0
}
```

## 7. Run it again

Every `apply` converges. The second run of the page and the menu:

```json
{
  "action": "unchanged",
  "id": "e7bd3df9-9bf6-417e-9813-cf7303c27147",
  "path": "/library-desk",
  "published": true
}
{
  "menuId": "280e295f-9422-4673-b884-ea3566f0fef2",
  "name": "Library",
  "created": 0,
  "updated": 0,
  "deleted": 0,
  "unchanged": 5
}
```

Open the *Library* app in the end-user app to see the desk page and the *Open loans* view. To move the whole app to
another tenant, export it as a package: [Sandboxes and promotion](/docs/platform/environments-and-promotion/).

**Next:** [Example 2 — automate it](/docs/examples/automate-library/).
