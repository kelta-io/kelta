import { test, expect } from "@playwright/test";
import { spawnSync } from "child_process";
import * as path from "path";
import * as fs from "fs";
import { fileURLToPath } from "url";

/**
 * Dispatcher reviewer stage + merge policy dispatch — validates
 * `lib/deploy-hooks.sh` (run_reviewer, apply_merge_policy, run_deploy_hook,
 * process_after_ci) by running the shell unit test in
 * `.claude/dispatcher/tests/review-test.sh`.
 *
 * Covers the six P-0 items 10-11 acceptance scenarios: CI-green + approve +
 * auto policy, first strike, second strike, veto_window, manual_protected,
 * and the review-prompt JSON-shape check.
 *
 * Skipped when the shell test is absent (different checkout / CI matrix
 * that doesn't include dispatcher files).
 */
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "../../..");
const SHELL_TEST = path.join(
  REPO_ROOT,
  ".claude/dispatcher/tests/review-test.sh",
);

test.describe("dispatcher: reviewer + merge policy", () => {
  test.skip(
    !fs.existsSync(SHELL_TEST),
    "shell test not present in this checkout",
  );

  test("review-test.sh passes all reviewer / merge-policy scenarios", () => {
    const result = spawnSync("bash", [SHELL_TEST], {
      encoding: "utf8",
      timeout: 60_000,
    });
    if (result.status !== 0) {
      throw new Error(
        `review-test.sh exited ${result.status}\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`,
      );
    }
    expect(result.stdout).toContain("passed, 0 failed");
  });
});
