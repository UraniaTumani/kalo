import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/lib/api/client'
import { AuthProvider } from '@/auth/AuthContext'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import App from './App'
import './index.css'
// Side-effect import: initialises i18next before any component renders.
import '@/i18n'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,

      /*
       * F14: a tab that lost focus did not catch up when you came back to it.
       *
       * The partner ride queue was the screen where that cost something real. A
       * dispatcher on another tab while a passenger booked came back to a queue
       * that did not show the new request, and the request expires on a clock —
       * so a configuration default was losing bookings. Reproduced as a test in
       * e2e/realtime.spec.ts before this line changed.
       *
       * Deliberately NOT paired with refetchIntervalInBackground. A hidden tab
       * stays silent: measured at zero requests while hidden, and that is worth
       * keeping, because the alternative is every idle tab in every company
       * polling for rides nobody is looking at. Catching up on return gets the
       * screen right without that traffic.
       *
       * Refetching on focus is gated by staleTime above, which is the behaviour
       * we want rather than a limitation: returning within ten seconds of the
       * last fetch cannot have missed more than a single poll period.
       */
      refetchOnWindowFocus: true,
      // 4xx responses are deliberate answers from the API, not flakiness.
      retry: (failureCount, error) =>
        error instanceof ApiError && error.status < 500 ? false : failureCount < 2,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          {/* Outer net for the pages that render outside AppLayout. */}
          <ErrorBoundary fullscreen>
            <App />
          </ErrorBoundary>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
