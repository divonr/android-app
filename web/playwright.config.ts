import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright E2E configuration for the LLM API web frontend.
 *
 * Tests drive the REAL built frontend served by the REAL Ktor server.
 * The server is started automatically via webServer config.
 *
 * Required environment variables (set in CI or locally before running):
 *   E2E_PASSWORD   — password to log in with (must match WEB_UI_PASSWORD)
 *   E2E_DATA_DIR   — temp dir for server data; auto-generated if unset
 *
 * Run: npm run test:e2e
 * Run with UI: npx playwright test --ui
 */
export default defineConfig({
  // Test directory
  testDir: './e2e',
  // Output directories
  outputDir: 'test-results',
  // Reporter
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  // All tests must complete within 30 seconds
  timeout: 30_000,
  // Expect assertions timeout
  expect: {
    timeout: 8_000,
  },
  // Fail fast in CI
  fullyParallel: false,
  // Retry on CI to handle flaky timing
  retries: process.env.CI ? 2 : 0,
  // Use only Chromium (Node 18 compatible)
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Base URL — the local server started by webServer below
        baseURL: 'http://localhost:8092',
        // Don't carry cookies between tests by default
        storageState: undefined,
      },
    },
  ],
  // Start the Ktor server before running tests.
  // We use port 8092 (not 8091) so E2E tests don't clash with a running dev server.
  webServer: {
    command: buildServerCommand(),
    url: 'http://localhost:8092/health',
    timeout: 20_000,
    reuseExistingServer: false,
    stdout: 'pipe',
    stderr: 'pipe',
  },
})

function buildServerCommand(): string {
  // When running via `npm run test:e2e` from within web/, cwd is the web/ dir.
  // Go up one level to get the repo root.
  const repoRoot = process.cwd().endsWith('/web')
    ? process.cwd().slice(0, -4)
    : process.cwd()
  const dataDir = process.env.E2E_DATA_DIR ?? `/tmp/llm-web-e2e-${Date.now()}`
  const password = process.env.E2E_PASSWORD ?? 'e2e-test-password'
  const script = `${repoRoot}/server/build/install/server/bin/server`

  // Build environment variables for the server process.
  // KTOR_PORT=8092 overrides the default 8091 so E2E tests don't clash with a
  // running dev server.
  const env = [
    `WEB_UI_PASSWORD=${password}`,
    `WEB_STATIC_DIR=${repoRoot}/web/dist`,
    `LLM_WEB_DATA_DIR=${dataDir}`,
    `PUBLIC_BASE_URL=http://localhost:8092`,
    'KTOR_PORT=8092',
  ].join(' ')

  return `${env} ${script}`
}
