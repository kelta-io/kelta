import { defineConfig, devices } from "@playwright/test";
import path from "path";
import { fileURLToPath } from "url";
import dotenv from "dotenv";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

dotenv.config({ path: path.resolve(__dirname, ".env.local") });
dotenv.config({ path: path.resolve(__dirname, ".env") });

const BASE_URL = process.env.E2E_BASE_URL || "https://kelta.io";
const CI = !!process.env.CI;

// Optional Chromium host remapping, e.g. "MAP auth.localhost kelta-auth". Chromium resolves
// every *.localhost name to its own loopback without asking DNS, so when the browser runs in
// a container next to the stack (CI) the local issuer http://auth.localhost:8081 must be
// pointed at the kelta-auth container explicitly. Unset everywhere else.
const HOST_RULES = process.env.E2E_BROWSER_HOST_RULES;
const launchOptions = HOST_RULES
  ? { args: [`--host-resolver-rules=${HOST_RULES}`] }
  : undefined;

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  forbidOnly: CI,
  retries: CI ? 1 : 0,
  workers: CI ? 1 : 2,
  timeout: 45_000,
  globalTimeout: CI ? 40 * 60 * 1000 : undefined,
  reporter: CI
    ? [
        ["html", { open: "never", outputFolder: "playwright-report" }],
        ["junit", { outputFile: "test-results/junit.xml" }],
        ["list"],
      ]
    : [["html", { open: "on-failure" }], ["list"]],

  use: {
    baseURL: BASE_URL,
    trace: CI ? "on-first-retry" : "retain-on-failure",
    screenshot: "only-on-failure",
    video: CI ? "on-first-retry" : "retain-on-failure",
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    launchOptions,
    viewport: { width: 1280, height: 800 },
  },

  projects: [
    {
      name: "auth-setup",
      testDir: "./auth",
      testMatch: "auth.setup.ts",
    },
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        storageState: "./auth/storage-state.json",
      },
      dependencies: ["auth-setup"],
    },
    // Firefox can be enabled once chromium tests are stable
    // ...(CI
    //   ? [
    //       {
    //         name: 'firefox',
    //         use: {
    //           ...devices['Desktop Firefox'],
    //           storageState: './auth/storage-state.json',
    //         },
    //         dependencies: ['auth-setup'],
    //       },
    //     ]
    //   : []),
  ],

  expect: {
    timeout: 10_000,
  },
});
