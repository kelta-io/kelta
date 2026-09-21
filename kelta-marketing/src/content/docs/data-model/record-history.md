---
title: Field history and record versioning
description: Two complementary change-tracking mechanisms — per-field history and full-record versions — what they capture, how to read them, and how security applies.
section: data-model
order: 80
---

| | Field history | Record versioning |
|---|---|---|
| Switch | `trackHistory` on a **field** | `trackHistory` on the **collection** |
| Captures | old and new value of that field per create/update/delete | full snapshot of the record per create/update/delete |
| Read from | `field-history` collection, `GET /api/field-history` | `record-versions` collection, `GET /api/record-versions` |
| UI | *History* tab on a record, activity timeline | *History* tab on a record |

Turning on collection-level versioning supersedes per-field tracking on that collection: every field is captured
in the snapshot, so the per-field hook stands down to avoid duplicate rows.

## Field history

Enable per field (Fields tab → *Track history*, or `trackHistory: true` on the `fields` record). Each change of a
tracked field on a user collection writes a `field-history` row: the record, the field, `oldValue`, `newValue`,
who changed it and when.

```http
GET /api/field-history?filter[recordId][eq]=<id>&sort=-createdAt
```

## Record versioning

Enable per collection (collection edit form → *Track history*, or `trackHistory: true` on the `collections`
record). Each create, update or delete writes a `record-versions` row: a monotonically increasing
`versionNumber`, `changeType` (`CREATED`, `UPDATED`, `DELETED`), the full `snapshot`, `changedFields`,
`changedBy`, `changedAt` and `changeSource`. No-op updates are skipped. Deleting a record keeps its versions; the
final one is `DELETED` and carries the last snapshot.

```http
GET /api/record-versions?filter[recordId][eq]=<id>&sort=-versionNumber
```

## Security

Both feeds are read through the same field-level security as the live record: rows for fields the caller cannot
see are dropped, and values of masked fields are redacted in `oldValue`/`newValue` and in snapshots. There is no
way to read a hidden field's history.

## What is not covered

- **Setup changes** (metadata edits) are recorded separately in the setup audit trail — see
  [Audit logs](/docs/security/audit-log/).
- Neither feed has a retention policy of its own yet; rows are kept indefinitely.
- Restoring a previous version is a manual operation: read the snapshot and write the values back.
