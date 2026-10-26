import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  testMatch: 'driver-invitation-integration.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 60_000,
  outputDir: './e2e/.results/invitation-integration',
  // No traces or screenshots that could retain the one-time test credential.
  use: { baseURL: 'http://localhost:5177', channel: 'chrome', trace: 'off', screenshot: 'off', video: 'off' },
  webServer: {
    command: 'npm run build && npm run preview -- --port 5177 --strictPort',
    url: 'http://localhost:5177',
    reuseExistingServer: false,
    timeout: 120_000,
    env: { VITE_DRIVER_INVITATIONS_ENABLED: 'true', VITE_API_URL: '', VITE_API_TARGET: 'http://127.0.0.1:18881' },
  },
})
