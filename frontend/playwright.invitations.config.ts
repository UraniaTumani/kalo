import { defineConfig } from '@playwright/test'

// Dedicated preview: no backend, database, Docker, or SMS provider.
export default defineConfig({
  testDir: './tests',
  testMatch: 'driver-invitations.spec.ts',
  outputDir: './e2e/.results/invitations',
  use: { baseURL: 'http://localhost:5176', channel: 'chrome' },
  webServer: {
    command: 'npm run build && npm run preview -- --port 5176 --strictPort',
    env: { VITE_DRIVER_INVITATIONS_ENABLED: 'true', VITE_API_URL: '' },
    url: 'http://localhost:5176',
    reuseExistingServer: false,
  },
})
