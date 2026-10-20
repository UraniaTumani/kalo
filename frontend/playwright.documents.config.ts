import { defineConfig } from '@playwright/test'

// API fixtures only: no backend, database, Docker or global E2E warmup.
export default defineConfig({
  testDir: './tests',
  testMatch: 'fleet-documents.spec.ts',
  outputDir: './e2e/.results/documents',
  use: { baseURL: 'http://localhost:5175', channel: 'chrome' },
  webServer: {
    command: 'npm run dev -- --port 5175 --strictPort',
    url: 'http://localhost:5175',
    reuseExistingServer: false,
  },
})
