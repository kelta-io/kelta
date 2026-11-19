# "Build kelta CLI" failures on kelta PR CI, 2026-11-05 to 2026-11-19 (PLT-988)

The `Build kelta CLI` step (`cd kelta-web && npm ci --no-audit --no-fund && npm run build`) runs in
the `e2e`, `templates` and `mcp-replay` jobs of `.github/workflows/ci.yml`. In the 14 days to
2026-11-19 it failed in five PR runs, all on Dependabot npm group branches. Each failing job's log
was read with `ci-log.sh emf <run-id> --failed --job <job-id>`. Every job log reports
`Secret source: Dependabot`, and its checkout is `Merge <dependabot head> into <main>`.

Classes: **(a)** the PR's own change broke the CLI build, so CI was right to fail; **(b)** a
registry or network transient during `npm ci`; **(c)** a defect in the workflow or the kelta-web
build that fails whatever the change.

| run | job id | job | head branch | author | signature | class |
|---|---|---|---|---|---|---|
| 44095106146 | 134080220711 | E2E Tests (shard 1/2) | `dependabot/npm_and_yarn/kelta-ui/app/npm_and_yarn-557e608cc1` (#2178) | Dependabot | TS 6.0 `tsc` TS2591 in `@kelta/cli` | (a) |
| 44095106146 | 134080220786 | E2E Tests (shard 2/2) | same | Dependabot | TS 6.0 `tsc` TS2591 in `@kelta/cli` | (a) |
| 44095106146 | 134080220765 | MCP Replay Call Count | same | Dependabot | TS 6.0 `tsc` TS2591 in `@kelta/cli` | (a) |
| 44095106146 | 134080220784 | Template Install Check | same | Dependabot | TS 6.0 `tsc` TS2591 in `@kelta/cli` | (a) |
| 43792421631 | 133053745672 | E2E Tests (shard 1/2) | `dependabot/npm_and_yarn/kelta-ui/app/npm_and_yarn-4bf553183d` (#2153) | Dependabot | `npm ci` ERESOLVE, typescript@7.0.2 vs eslint-plugin@8.72.2 | (a) |
| 43792421631 | 133053745596 | E2E Tests (shard 2/2) | same | Dependabot | same ERESOLVE | (a) |
| 43792421631 | 133053745578 | MCP Replay Call Count | same | Dependabot | same ERESOLVE | (a) |
| 43792421631 | 133053745692 | Template Install Check | same | Dependabot | same ERESOLVE | (a) |
| 43790796602 | 133048605902 | E2E Tests (shard 1/2) | same 4bf553183d | Dependabot | same ERESOLVE (eslint-plugin@8.72.2) | (a) |
| 43790796602 | 133048605908 | E2E Tests (shard 2/2) | same | Dependabot | same ERESOLVE | (a) |
| 43790796602 | 133048605999 | MCP Replay Call Count | same | Dependabot | same ERESOLVE | (a) |
| 43790796602 | 133048605950 | Template Install Check | same | Dependabot | same ERESOLVE | (a) |
| 43297075879 | 131458280047 | E2E Tests (shard 1/2) | same 4bf553183d | Dependabot | `npm ci` ERESOLVE, typescript@7.0.2 vs eslint-plugin@8.72.1 | (a) |
| 43297075879 | 131458280072 | E2E Tests (shard 2/2) | same | Dependabot | same ERESOLVE | (a) |
| 43297075879 | 131458281482 | MCP Replay Call Count | same | Dependabot | same ERESOLVE | (a) |
| 43297075879 | 131458280269 | Template Install Check | same | Dependabot | same ERESOLVE | (a) |
| 42399942558 | 128485414813 | E2E Tests (shard 1/2) | same 4bf553183d | Dependabot | same ERESOLVE (eslint-plugin@8.72.1) | (a) |
| 42399942558 | 128485414831 | E2E Tests (shard 2/2) | same | Dependabot | same ERESOLVE | (a) |
| 42399942558 | 128485414722 | MCP Replay Call Count | same | Dependabot | same ERESOLVE | (a) |
| 42399942558 | 128485414732 | Template Install Check | same | Dependabot | same ERESOLVE | (a) |

The failure lines, identical in every job of each signature:

- **ERESOLVE** (16 jobs on `4bf553183d`): `npm ci` stops before `npm run build` runs:
  ```
  npm error code ERESOLVE
  npm error While resolving: @typescript-eslint/eslint-plugin@8.72.2   (8.72.1 in runs 43297075879, 42399942558)
  npm error Found: typescript@7.0.2
  npm error   dev typescript@"^7.0.2" from the root project
  npm error peer typescript@">=4.8.4 <6.1.0" from @typescript-eslint/eslint-plugin@8.72.2
  ##[error]Process completed with exit code 1.
  ```
- **TS2591** (4 jobs on `557e608cc1`): `npm ci` succeeds (`added 677 packages in 9s`), then the
  `@kelta/cli` `tsc` step of `npm run build` fails:
  ```
  > @kelta/cli@0.1.0 build
  > tsc
  ##[error]src/admin/fieldBody.test.ts(1,30): error TS2591: Cannot find name 'node:fs'. Do you need to install type definitions for node? ...
  ##[error]src/auth/browser.ts(9,5): error TS2591: Cannot find name 'process'. ...
  ```
  The Test Frontend job in the same run fails at `tsc --noEmit` with
  `tsconfig.json(21,5): error TS5101: Option 'baseUrl' is deprecated and will stop functioning in
  TypeScript 7.0. Specify compilerOption '"ignoreDeprecations": "6.0"'`. That diagnostic exists
  only in TypeScript 6.0. TypeScript 6.0 also changed the default of `compilerOptions.types` to
  `[]`, so `@types/node` stops being included automatically. #2178 opened after the
  `typescript >= 6.1.0` Dependabot ignore (kelta#2173) and bumps typescript from 5.9.3 to 6.0.x.
  The job logs do not print the exact version. 6.0.3 is the newest release under that ignore, and
  installing `typescript@6.0.3` in `kelta-web` and in `kelta-web/packages/cli` on main reproduces
  the same TS5101 and the same `@kelta/cli` TS2591 lines locally.

## Conclusion

Every one of the 20 jobs is class **(a)**: each Dependabot bump broke the build it was proposing.
No log has a network or registry signature (`ETIMEDOUT`, `ECONNRESET`, `EAI_AGAIN`, `EINTEGRITY`,
5xx), and `kelta-web` on main builds cleanly (`npm ci --no-audit --no-fund && npm run build`, exit
0). So `ci.yml` and `kelta-web/` stay unchanged. A `npm ci` retry would not have helped, because
both failures are deterministic.

The TypeScript 7 bump is already ignored (`typescript >= 6.1.0` in `.github/dependabot.yml`).
The TypeScript 6.0 bump still needs one of two things before Dependabot stops reproposing it:
an ignore that extends to `>= 6.0.0`, or a kelta-web migration to TypeScript 6 (declare
`"types": ["node"]` where Node globals are used, drop or replace `baseUrl`).
