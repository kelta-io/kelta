---
title: "Tutorial: your first app"
description: Build a small Invoices app end to end — collection, fields, picklist, layout, list view and navigation — in the console, then the same thing from the CLI and from an MCP client.
section: getting-started
order: 30
---

This tutorial builds an **Invoices** app: a collection with a customer lookup, an amount, a due date and a status
picklist, then the pieces that make it usable — a page layout, a kanban list view and a tab in the end-user app.
You will do it three ways. Each way produces the same metadata, so pick whichever fits how you work.

You need a running Kelta ([quickstart](/docs/getting-started/quickstart/)) and an administrator login.

## What you will build

| Object | Purpose |
|---|---|
| `customers` collection | one field, `name` — the target of the lookup |
| `invoices` collection | `number`, `customer` (lookup), `amount` (currency), `due_date` (date), `status` (picklist) |
| `invoice-status` global picklist | `DRAFT`, `SENT`, `PAID`, `OVERDUE` |
| validation rule | an amount must be positive |
| `Main` layout | how a single invoice is displayed |
| `By status` list view | a kanban board with one lane per status |
| `Finance` app | a top-nav app with an Invoices tab |

## Part 1 — In the admin console

### 1. Create the collections

Open **Setup → Data model → Collections → Create collection**. The wizard has four steps: **Basics** (API name
`customers`, display name *Customers*), **Fields** (add `name`, type *Text*, required), **Authorization** (tick
Create/Read/Edit/Delete for the profiles that should use it) and **Review**. Click **Create collection**.

Repeat for `invoices`. On the Fields step add:

| Field | Type | Options |
|---|---|---|
| `number` | Text | required, unique |
| `customer` | Lookup | target collection `customers`, required |
| `amount` | Currency | required |
| `due_date` | Date | |

Leave `status` for the next step — it needs a picklist first.

Behind the scenes each collection gets a physical table, a `/api/<name>` endpoint and an entry in the gateway's
route table; you can already `GET /api/invoices`. See [Collections](/docs/data-model/collections/).

### 2. Add the status picklist

Open **Setup → Data model → Picklists → Create picklist**, name it `invoice-status` and add the values `DRAFT`,
`SENT`, `PAID`, `OVERDUE` (mark `DRAFT` as default). Then, back in the `invoices` collection, add a field `status`
of type *Picklist* and choose the `invoice-status` global picklist as its source.

### 3. Add a validation rule

Open the `invoices` collection → **Validation rules → New rule**:

- Name: `Positive amount`
- Error condition: `amount <= 0`
- Message: `Amount must be greater than zero`

The formula is an **error condition**: the record is rejected when it evaluates to `TRUE`. See
[Validation rules](/docs/data-model/validation-rules/).

### 4. Lay out the record page

Open **Setup → Data model → Page layouts → Create layout** for `invoices`, name it `Main` and mark it as the
default. Without a layout the record page still renders — every field in one section — so this step is about
control, not necessity. Put `number` and `customer` in the first section's two columns, then `amount`, `due_date`
and `status` in a second section named *Billing*. Columns are 0-based when you edit the JSON directly.
See [Page layouts](/docs/console/page-layouts/).

### 5. Save a kanban list view

Open **Setup → Data model → List views → New list view** for `invoices`: name it `By status`, choose the columns
`number`, `customer`, `amount`, `due_date`, set the renderer to **Kanban** with `status` as the lane field, and set
visibility to *Public* so everyone sees it. See [List views](/docs/console/list-views/).

### 6. Put it in an app

Open **Setup → UI customization → Menus → New menu**, name it `Finance` and add an item *Invoices* with the path
`/resources/invoices`. Mark the menu as default if it should be the first app users land in. See
[Apps and navigation menus](/docs/console/apps-and-menus/).

### 7. Use it

Switch to the end-user app (the app switcher in the header, or open `/<tenant>/app/o/invoices`). Create a
customer, then an invoice; drag the invoice between lanes in the *By status* view. Try to save an invoice with a
negative amount and read the validation error.

## Part 2 — From the CLI

Install and log in first ([Install the CLI](/docs/getting-started/install-cli/)). Every command prints JSON when
piped, so the same script works for a person and for CI.

```bash
kelta collections create --name customers --display-name Customers
kelta fields add customers --name name --type text --required

kelta collections create --name invoices --display-name Invoices
kelta fields add invoices --name number   --type text --required --unique
kelta fields add invoices --name customer --type lookup --reference customers --required
kelta fields add invoices --name amount   --type currency --required
kelta fields add invoices --name due_date --type date

kelta picklists create --name invoice-status --values DRAFT,SENT,PAID,OVERDUE
kelta fields add invoices --name status --type picklist --picklist invoice-status

kelta validation-rules create invoices \
  --name "Positive amount" \
  --formula "amount <= 0" \
  --message "Amount must be greater than zero"
```

Layouts and menus are applied as trees from a file — one idempotent call per object:

```bash
cat > invoices-layout.json <<'JSON'
{
  "name": "Main",
  "layoutType": "DETAIL",
  "isDefault": true,
  "headerConfig": { "titleFields": ["number"] },
  "sections": [
    { "heading": "Overview", "columns": 2,
      "fields": [ { "name": "number", "column": 0 }, { "name": "customer", "column": 1 } ] },
    { "heading": "Billing", "columns": 2,
      "fields": [ { "name": "amount", "column": 0 }, { "name": "due_date", "column": 1 }, { "name": "status", "column": 0 } ] }
  ]
}
JSON
kelta layouts apply invoices --file invoices-layout.json

kelta list-views apply invoices \
  --name "By status" \
  --columns number,customer,amount,due_date \
  --view-type KANBAN --lane-field status \
  --visibility PUBLIC

cat > finance-menu.json <<'JSON'
{
  "name": "Finance",
  "isDefault": true,
  "items": [ { "label": "Invoices", "path": "/resources/invoices" } ]
}
JSON
kelta menus apply --file finance-menu.json
```

Run the `apply` commands a second time: every one reports `unchanged`. That is the property that makes them safe to
keep in a setup script or a CI job. See the [CLI command reference](/docs/cli/commands/).

## Part 3 — From an MCP client

Connect an MCP client to the tenant's **admin** endpoint ([Connecting](/docs/mcp/connecting/)) and ask it to build
the app. The admin toolset follows the same build order the console does:

1. `create_collection` (`customers`, then `invoices` with an inline initial field set)
2. `add_field` for any field not created inline
3. `apply_picklist` for `invoice-status`, then `add_field` for `status` referencing it
4. `create_validation_rule`
5. `apply_layout` — the same tree document as the CLI file above
6. `apply_listview` — kanban on `status`
7. `apply_menu` — the `Finance` app with its Invoices item

Every `apply_*` tool is create-or-update keyed on a natural name and reports `{"action": "created" | "updated" |
"unchanged", "id": ..., "changed": [...]}`, so an agent can re-run its plan until it converges. Before authoring by
hand, an agent should read the `kelta://docs/page-layouts`, `kelta://docs/list-views` and `kelta://docs/ui-menus`
resources — they are the same documents as the [authoring reference](/docs/reference/page-layouts/) on this site.

## Optional: a custom page

The page builder lets you compose a page from widgets bound to your data. A minimal page that lists invoices:

```json
{
  "schemaVersion": 2,
  "dataSources": [ { "name": "invoices", "collection": "invoices", "mode": "list", "limit": 50 } ],
  "components": [
    { "id": "h1", "type": "heading", "props": { "text": "Open invoices", "level": "h2" } },
    { "id": "t1", "type": "table", "props": { "source": { "$bind": "data.invoices" } } }
  ]
}
```

Create it in **Setup → UI customization → Pages** (or `kelta pages apply page.json` then
`kelta pages publish /open-invoices`) and add `/p/open-invoices` to the Finance menu. See
[Page builder](/docs/console/page-builder/).

## Where next

- Model it properly: [Field types](/docs/data-model/field-types/), [Relationships](/docs/data-model/relationships/)
- Automate it: send an email when an invoice becomes `OVERDUE` — [Flows](/docs/automation/flows/)
- Secure it: who can see `amount`? — [Field-level security](/docs/security/field-security-and-masking/)
- Move it: export the metadata as a package and promote it — [Sandboxes and promotion](/docs/platform/environments-and-promotion/)
