import { defineConfig, devices } from "@playwright/test";

// Ports are overridable so two runs (for example a builder and a checker) never collide.
const WEB_PORT = Number(process.env.E2E_WEB_PORT ?? 3100);
const MOCK_PORT = Number(process.env.E2E_MOCK_PORT ?? 4010);

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  // AUD-TP-6: on CI a test that passed only on its retry is flaky; the github reporter annotates it and the JSON report
  // feeds the "flaky e2e tests" step in ci.yml, so a flake never passes silently.
  reporter: process.env.CI ? [["list"], ["github"], ["json", { outputFile: "test-results/results.json" }]] : [["list"]],
  use: { baseURL: `http://127.0.0.1:${WEB_PORT}`, trace: "retain-on-failure" },
  // PW_CHROMIUM_PATH points at a pre-installed Chromium (the cloud sandbox ships one at /opt/pw-browsers/chromium); CI
  // leaves it unset and uses `npx playwright install chromium`.
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], launchOptions: { executablePath: process.env.PW_CHROMIUM_PATH || undefined } } }],
  // Smoke tests run against the mock API generated from the contract (mock/server.ts); there is no live backend on Day 1.
  webServer: [
    {
      command: "npx tsx mock/server.ts",
      url: `http://127.0.0.1:${MOCK_PORT}/__mock/health`,
      env: { MOCK_PORT: String(MOCK_PORT) },
      reuseExistingServer: !process.env.CI,
    },
    {
      // Requires a prior `npm run build` (scripts/ci.sh e2e does it).
      command: "node scripts/start-standalone.mjs",
      url: `http://127.0.0.1:${WEB_PORT}/login`,
      env: {
        PORT: String(WEB_PORT),
        HOSTNAME: "127.0.0.1",
        ARON_API_BASE_URL: `http://127.0.0.1:${MOCK_PORT}`,
        ARON_SESSION_SECRET: "e2e-only-session-secret-0123456789abcdef",
        ARON_COOKIE_INSECURE: "1",
        ARON_BLOB_ORIGIN: `http://127.0.0.1:${MOCK_PORT}`,
      },
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
  ],
});
