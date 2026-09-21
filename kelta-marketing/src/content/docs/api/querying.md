---
title: Pagination, filtering, sorting, includes
description: The query parameters every list endpoint understands — page[number]/page[size], the filter grammar and operators, sort, include, sparse fieldsets — plus the changes feed and optimistic locking.
section: api
order: 30
---

## Pagination

```http
GET /api/customers?page[number]=2&page[size]=50
```

| Parameter | Default | Maximum |
|---|---|---|
| `page[number]` | 1 | — (≤ 0 clamps to 1) |
| `page[size]` | 20 | 200 (larger values clamp silently; `meta.pageSizeClamped` is set) |

Only the bracket form is honoured; `pageNumber`/`pageSize` fall back to defaults. Responses carry `meta`
(`totalCount`, `currentPage`, `pageSize`, `totalPages`) and `links` (`self`, `prev`, `next` — relative URLs that
preserve your other parameters). `metadata` duplicates `meta` for older clients and is deprecated.

## Filtering

```
filter[<field>][<op>]=<value>
filter[<field>]=<value>          # shorthand for [eq]
```

| Operator | Meaning |
|---|---|
| `eq`, `neq` | equals / not equals |
| `gt`, `gte`, `lt`, `lte` | comparisons (numbers, dates) |
| `in` | any of a comma-separated list — `filter[status][in]=SENT,PAID` (alias `any`) |
| `contains`, `starts`, `ends` | substring, prefix, suffix |
| `icontains`, `istarts`, `iends`, `ieq` | case-insensitive variants |
| `isnull` | `true` / `false` |

Multiple filters combine with **AND**. There is no OR across fields — run two queries and merge, or use a saved
list view with a formula. Any other `filter…` key shape (`filter[x][in][]=…`, indexed variants) is rejected with
`400 INVALID_QUERY` rather than silently ignored.

Filtering on a field that is masked for the caller answers `403 MASKED_FIELD_PREDICATE`; formula fields cannot be
filtered.

## Sorting

```http
GET /api/invoices?sort=-due_date,number
```

Comma-separated fields, `-` prefix for descending. Sorting on a masked field is rejected like filtering.

## Includes

```http
GET /api/invoices?include=customer            # to-one: name the lookup field
GET /api/customers?include=invoices           # has-many: name the child collection
GET /api/customers?include=invoices,invoice-lines   # grandchildren resolve through the included child
```

Included resources land in `included[]` and obey field-level security. See
[Relationships](/docs/data-model/relationships/#includes).

## Sparse fieldsets

```http
GET /api/invoices?fields[invoices]=number,amount&include=customer&fields[customers]=name
```

## Changes feed (sync)

```http
GET /api/invoices/_changes?since=2026-09-01T00:00:00Z
```

Returns record **deletions** since the cursor (from tombstones) plus a fresh cursor. Fetch upserts with the normal
list endpoint: `filter[updatedAt][gt]=<cursor>&sort=updatedAt`. This is what the app's offline mode uses.

## Optimistic locking

Single-record reads return an `ETag`. Send it back as `If-Match` on `PATCH` or `DELETE` to have the write
rejected with `409` if the record changed in between. The kanban board uses this when it moves cards.

## Full-text and semantic search

`GET /api/_search?q=…` across collections and `POST /api/<collection>/semantic-search` — see
[Search and attachments](/docs/data-model/search-and-attachments/).
