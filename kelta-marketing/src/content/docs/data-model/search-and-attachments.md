---
title: Search and attachments
description: Full-text search across collections, semantic (vector) search, index maintenance, and the attachment lifecycle with image transforms.
section: data-model
order: 90
---

## Full-text search

Mark fields as `searchable`; their values are folded into a per-record `tsvector` index together with the
record's display value. Search across every collection the caller can read:

```http
GET /api/_search?q=acme&limit=20
```

- `q` must be at least 3 characters; `limit` is clamped to 1–100 (default 20).
- Results carry the collection, record id, display value and a snippet, ordered by rank.
- The end-user app exposes it as global search (`Cmd/Ctrl+K`, `/app/search`); the MCP user toolset as `search`.
- Fields with a masking configuration are never indexed.

Rebuild the index after bulk changes with `POST /api/admin/search-reindex` (Setup → Platform → Search index).

## Semantic search

Give a collection a `VECTOR` field with an `embeddingSource` (a text field). On every write the source text is
embedded and stored; a pgvector HNSW cosine index is created automatically.

```http
POST /api/<collection>/semantic-search
{ "query": "late payment from a long-standing customer", "limit": 10 }
```

Results are records ordered by cosine distance (returned in `meta`) and filtered by field-level security. MCP:
`semantic_search`.

**Embedding provider.** The platform ships an `EmbeddingService` interface with a dependency-free hashing
implementation as the default so the feature works everywhere; result quality with the default is limited. A
deployment plugs in a real model by providing its own `EmbeddingService` bean, and `pgvector` must be installed in
the database. Masking-configured source fields are never embedded; toggling masking purges existing vectors.

## Attachments

Files attach to any record through the `attachments` system collection. Storage is S3-compatible object storage
(`KELTA_S3_*` configuration — see [Configuration reference](/docs/deploy/configuration/)).

1. **Upload** — `POST /api/attachments/upload-url` with the file's name, type, size and target
   `collectionId`/`recordId`. The request is validated (type, size, the tenant's storage quota), a pending row is
   created and a presigned `PUT` URL returned. Upload the bytes straight to that URL, then
   `POST /api/attachments/{id}/finalize`.
2. **List and download** — `GET /api/attachments?filter[collectionId][eq]=…&filter[recordId][eq]=…`, or
   `?include=attachments` on the record. Each resource carries a presigned `downloadUrl`;
   `GET /api/files/{storageKey}` streams server-side with `Range` support.
3. **Delete** — `DELETE /api/attachments/{id}` removes the object and the row. Deleting the parent record removes
   its attachments too.

### Image transforms

Images can be resized on the fly:

```
GET /api/images/<storageKey>?w=400&h=300&fit=cover&format=webp&quality=80
```

`fit` is `cover` or `contain` (default `contain`); `format` is `webp`, `jpeg` or `png`.
