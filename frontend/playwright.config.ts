import { defineConfig, devices } from '@playwright/test'

/**
 * Browser-level tests against the real frontend and the real backend.
 *
 * This layer exists for what the 173 backend tests cannot see: whether the
 * screens wire up to those rules correctly. It does not re-test the rules
 * themselves — a suspended user being refused is a backend test; a suspended
 * user being unable to get past the login screen is this one.
 *
 * Nothing here mocks the API. The one exception is the third-party geocoder,
 * which is rate-limited by its own usage policy and is not ours to hammer on
 * every run; see `e2e/support/fixtures.ts`.
 */

/* 5174 and 18080, not 5173 and 8080: a developer's own stack keeps running. */
const FRONTEND = process.env.E2E_BASE_URL ?? 'http://localhost:5174'
const API_TARGET = process.env.E2E_API_TARGET ?? 'http://localhost:18080'

/**
 * The bundled Chromium download is blocked on some networks, and Chrome is
 * already installed nearly everywhere this runs. `npx playwright install chrome`
 * covers CI.
 */
const CHANNEL = process.env.E2E_CHANNEL ?? 'chrome'

export default defineConfig({
  testDir: './e2e',

  /* Compiles every lazy route once, so no test pays that cost for the others. */
  globalSetup: './e2e/support/warmup.ts',
  outputDir: './e2e/.results',

  /* A real ride goes through six state transitions; the default 30s is tight. */
  timeout: 60_000,
  /*
   * Generous, because the dev server compiles each lazily-loaded route the
   * first time a test visits it. The alternative is a suite that passes on a
   * warm machine and fails on a cold one, which is worse than slow.
   */
  expect: { timeout: 20_000 },

  /* Serial locally: these share one backend and one database. */
  fullyParallel: false,
  workers: 1,

  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,

  reporter: process.env.CI
    ? [['list'], ['html', { outputFolder: './e2e/.report', open: 'never' }]]
    : [['list']],

  use: {
    baseURL: FRONTEND,
    channel: CHANNEL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    /*
     * Raised from 15s after four separate false failures, every one of them a
     * page that had not finished rendering rather than a wrong assertion: the
     * shell missing, a form field missing, a lazy route still compiling. Each
     * cost a full suite re-run to disprove.
     *
     * This does not weaken any assertion. It only changes how long the suite
     * is willing to wait for something that is supposed to appear, and on a
     * loaded machine 15s was not long enough for a cold route.
     */
    actionTimeout: 30_000,
  },

  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], channel: CHANNEL, viewport: { width: 1280, height: 900 } },
    },
  ],

  /*
   * Reuses whatever is already running locally, which is the normal case while
   * developing. CI starts both itself. The backend is not listed here: it is
   * started by the workflow (or by the developer) because it needs a database
   * and a profile, and Playwright starting a Spring Boot app would hide those.
   */
  webServer: process.env.E2E_NO_SERVER
    ? undefined
    : {
        command: 'npm run dev -- --port 5174 --strictPort',
        url: FRONTEND,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        stdout: 'ignore',
        stderr: 'pipe',
        env: { ...process.env, VITE_API_TARGET: API_TARGET },
      },
})
