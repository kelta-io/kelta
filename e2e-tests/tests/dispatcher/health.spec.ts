import { test, expect } from "@playwright/test";
import { spawnSync } from "child_process";
import * as path from "path";
import * as fs from "fs";
import { fileURLToPath } from "url";

/**
 * Dispatcher argocd_image_bump + post-deploy health check — validates
 * `_hook_argocd_image_bump` and `run_health_check` in `lib/deploy-hooks.sh`
 * by running the shell unit test in `.claude/dispatcher/tests/health-test.sh`.
 *
 * Covers the five OPERATING-MODEL.md §9/§10 item 12 acceptance scenarios:
 * DRY_RUN bump prints the expected line + writes the deploy-health marker,
 * healthy 200 curl stub returns 0 with no revert, unhealthy 503 stub reverts
 * and posts to #rzware-ops, missing health-state warns and returns 0, and
 * `bash -n lib/deploy-hooks.sh` exits 0.
 *
 * Skipped when the shell test is absent (different checkout / CI matrix
 * that doesn't include dispatcher files).
 */
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "../../..");
const SHELL_TEST = path.join(
  REPO_ROOT,
  ".claude/dispatcher/tests/health-test.sh",
);

test.describe("dispatcher: argocd image bump + health check", () => {
  test.skip(
    !fs.existsSync(SHELL_TEST),
    "shell test not present in this checkout",
  );

  test("health-test.sh passes all bump / health-check scenarios", () => {
    const result = spawnSync("bash", [SHELL_TEST], {
      encoding: "utf8",
      timeout: 60_000,
    });
    if (result.status !== 0) {
      throw new Error(
        `health-test.sh exited ${result.status}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`,
      );
    }
    expect(result.stdout).toContain("passed, 0 failed");
  });
});
