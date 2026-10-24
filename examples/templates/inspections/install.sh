#!/usr/bin/env bash
# Installs the inspections checklist template into the tenant of the active kelta profile
# (`kelta profile use <name>` or KELTA_PROFILE / KELTA_URL / KELTA_TENANT / KELTA_TOKEN).
# metadata.json is the whole template: collections, fields, picklists, layouts, the validation
# rule, the flow, list views, the dashboard and the seed records (its `records` sections, parents
# first; each reference names its parent by natural key). Re-running it creates nothing new.
cd "$(dirname "$0")" && exec kelta metadata apply metadata.json --yes
