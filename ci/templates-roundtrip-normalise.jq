# Normalises a `kelta metadata export` package for ci/templates-roundtrip-check.sh, which diffs
# a template's export from the tenant it was installed into against the export of the same
# package after it was applied to an empty sandbox. Two exports of the same metadata from two
# tenants differ only in ids and timestamps; this filter removes exactly those, so whatever is
# left in the diff is metadata the round trip lost or changed.
#
# Keys removed:
#   exportedAt                 (package)        when the export ran
#   source.instanceId          (package)        id of the exporting platform instance
#   source.tenantId            (package)        id of the exporting tenant
#   source.tenantSlug          (package)        the exporting tenant's slug (its id in URLs);
#                                               with the two above it is the whole `source`
#                                               block, so `source` is removed
#   id                         (items[].data)   the row's own id in the exporting tenant
#   <name>_id                  (items[].data)   foreign-key ids in the exporting tenant
#                                               (collection_id, layout_id, section_id, field_id,
#                                               display_field_id, picklist_source_id, …); the
#                                               importer resolves each through its *_name sibling
#   created_at, updated_at     (items[].data)   row timestamps
#   field_type_config.globalPicklistId
#                              (items[].data)   a FIELD's global picklist id; it travels as
#                                               global_picklist_name
#
# Nothing else is removed or rewritten. The items are put in one order (by type, then by their
# remaining content with keys sorted): the order rows come back from the database is not package
# content, and the importer applies items by type, not by position.

def canonical:
  walk(if type == "object" then to_entries | sort_by(.key) | from_entries else . end);

def strip_row:
  with_entries(select(.key | (. == "id" or endswith("_id") or . == "created_at" or . == "updated_at") | not))
  | if (.field_type_config | type) == "object" then .field_type_config |= del(.globalPicklistId) else . end;

del(.exportedAt, .source)
| .items |= (map(if (.data | type) == "object" then .data |= strip_row else . end)
             | sort_by(.type, (.data | canonical | tojson)))
