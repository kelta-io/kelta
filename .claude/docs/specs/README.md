# Specs

Every live file in this directory (anything outside `_archive/`) is **open Kelta backlog**: a
spec or slice that has not shipped yet. The planner feeds tasks from these files, so a spec
left here after it lands gets filed again as new work.

**Archive on ship.** A spec moves to `_archive/` in the same PR that ships or supersedes it,
keeping its group subdirectory and file name (`specs/<group>/<file>.md` →
`specs/_archive/<group>/<file>.md`, moved with `git mv`). Fix the relative links that point at
it, and the ones inside it, in that PR. A parent spec (a group `README.md` or
`page-builder-parity.md`) stays live while any of its slices is still open.

| Spec | Open work |
|------|-----------|
| [`page-builder-parity.md`](page-builder-parity.md) | Parent; slices in `_archive/page-builder/` |
| [`consumer-alerting/`](consumer-alerting/README.md) | Parent; slices in `_archive/consumer-alerting/` |
| [`kelta-cli/`](kelta-cli/README.md) | Slices 4–6 (partially shipped, see each `## Open`) |
| [`member-data-ownership/`](member-data-ownership/README.md) | Slice 5 — tenant adoption |
| [`module-platform/`](module-platform/README.md) | Slice 3 — lifecycle |
| [`support-mailbox/`](support-mailbox/README.md) | Slices 8–10 + `aws-setup.md` |

Shipped and superseded specs: [`_archive/`](_archive/).
