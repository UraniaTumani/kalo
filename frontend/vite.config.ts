import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // Same-origin in dev, so the backend needs no CORS configuration.
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  /*
   * `preview` serves the built output, and the E2E suite runs against it.
   *
   * It used to run against `vite dev`, which compiles a module the first time
   * something asks for it. Every route here is lazy, so each first visit waited
   * on a transform — and when that stalled, the dynamic import never resolved,
   * the app sat on its Suspense fallback, and the page never got as far as
   * calling the API. It looked like a hung request; the backend was idle at
   * 0.3% CPU answering health probes in 8ms throughout, because nothing had
   * asked it for anything.
   *
   * Built chunks are static files, so there is nothing left to stall on. It is
   * also what actually ships, which is what an end-to-end suite is for.
   *
   * preview.proxy is separate from server.proxy and does not inherit it.
   */
  preview: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
