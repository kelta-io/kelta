import { test as base, expect } from "@playwright/test";
import { DataFactory } from "../helpers/data-factory";
import { getApiToken, clearTokenCache } from "./auth-tokens";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SESSION_TOKENS_PATH = path.resolve(
  __dirname,
  "../auth/session-tokens.json",
);

interface EMFFixtures {
  tenantSlug: string;
  apiBaseUrl: string;
  dataFactory: DataFactory;
}

export const test = base.extend<EMFFixtures>({
  tenantSlug: [process.env.E2E_TENANT_SLUG || "default", { option: true }],
  apiBaseUrl: [
    process.env.E2E_API_BASE_URL || "https://kelta.io",
    { option: true },
  ],

  // Override the page fixture to inject the direct-login tokens before each test.
  // The app keeps tokens in localStorage (captured by storageState) and adopts a
  // session left in sessionStorage under the legacy key, which is what this injects.
  page: async ({ page }, use) => {
    if (fs.existsSync(SESSION_TOKENS_PATH)) {
      const tokens = JSON.parse(fs.readFileSync(SESSION_TOKENS_PATH, "utf-8"));
      await page.addInitScript((tokenMap: Record<string, string>) => {
        for (const [key, value] of Object.entries(tokenMap)) {
          sessionStorage.setItem(key, value);
        }
      }, tokens);
    }
    await use(page);
  },

  dataFactory: async ({ tenantSlug, apiBaseUrl }, use) => {
    const token = await getApiToken();
    const factory = new DataFactory({
      baseUrl: apiBaseUrl,
      token,
      tenantSlug,
      refreshToken: async () => {
        clearTokenCache();
        return getApiToken();
      },
    });
    await use(factory);
    await factory.cleanup();
  },
});

export { expect };
