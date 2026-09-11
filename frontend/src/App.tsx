import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth, homePathFor } from '@/auth/AuthContext'
import { ProtectedRoute } from '@/auth/ProtectedRoute'
import { AppLayout } from '@/components/AppLayout'
import { Spinner } from '@/components/ui/Spinner'
import { LoginPage } from '@/features/auth/LoginPage'
import { RegisterPage } from '@/features/auth/RegisterPage'

// Loaded on demand: no role pulls in another role's screens, and Leaflet only
// reaches the browser on the pages that actually show a map.
const BookRidePage = lazy(() =>
  import('@/features/customer/BookRidePage').then((m) => ({ default: m.BookRidePage })),
)
const CurrentRidePage = lazy(() =>
  import('@/features/customer/CurrentRidePage').then((m) => ({ default: m.CurrentRidePage })),
)
const RideHistoryPage = lazy(() =>
  import('@/features/customer/RideHistoryPage').then((m) => ({ default: m.RideHistoryPage })),
)
const PartnerDashboardPage = lazy(() =>
  import('@/features/partner/PartnerDashboardPage').then((m) => ({
    default: m.PartnerDashboardPage,
  })),
)
const PartnerRidesPage = lazy(() =>
  import('@/features/partner/PartnerRidesPage').then((m) => ({ default: m.PartnerRidesPage })),
)
const PartnerDriversPage = lazy(() =>
  import('@/features/partner/PartnerDriversPage').then((m) => ({ default: m.PartnerDriversPage })),
)
const PartnerVehiclesPage = lazy(() =>
  import('@/features/partner/PartnerVehiclesPage').then((m) => ({
    default: m.PartnerVehiclesPage,
  })),
)
const PartnerAssignmentsPage = lazy(() =>
  import('@/features/partner/PartnerAssignmentsPage').then((m) => ({
    default: m.PartnerAssignmentsPage,
  })),
)
const PartnerDocumentsPage = lazy(() =>
  import('@/features/partner/PartnerDocumentsPage').then((m) => ({
    default: m.PartnerDocumentsPage,
  })),
)
const PartnerSettingsPage = lazy(() =>
  import('@/features/partner/PartnerSettingsPage').then((m) => ({
    default: m.PartnerSettingsPage,
  })),
)
const PartnerAvailabilityPage = lazy(() =>
  import('@/features/partner/PartnerAvailabilityPage').then((m) => ({
    default: m.PartnerAvailabilityPage,
  })),
)
const AdminVerificationPage = lazy(() =>
  import('@/features/admin/AdminVerificationPage').then((m) => ({
    default: m.AdminVerificationPage,
  })),
)
const AdminCompaniesPage = lazy(() =>
  import('@/features/admin/AdminCompaniesPage').then((m) => ({ default: m.AdminCompaniesPage })),
)
const AdminUsersPage = lazy(() =>
  import('@/features/admin/AdminUsersPage').then((m) => ({ default: m.AdminUsersPage })),
)
const AdminRidesPage = lazy(() =>
  import('@/features/admin/AdminRidesPage').then((m) => ({ default: m.AdminRidesPage })),
)

/** Sends a signed-in user to their own surface, and everyone else to login. */
function RootRedirect() {
  const { role, loading } = useAuth()

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Spinner label="Loading" />
      </div>
    )
  }

  return <Navigate to={homePathFor(role)} replace />
}

export default function App() {
  return (
    <Suspense
      fallback={
        <div className="flex h-screen items-center justify-center">
          <Spinner />
        </div>
      }
    >
      <Routes>
      <Route path="/" element={<RootRedirect />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route element={<ProtectedRoute allow={['CUSTOMER']} />}>
        <Route element={<AppLayout />}>
          <Route path="/ride" element={<BookRidePage />} />
          <Route path="/ride/current" element={<CurrentRidePage />} />
          <Route path="/ride/history" element={<RideHistoryPage />} />
        </Route>
      </Route>

      <Route element={<ProtectedRoute allow={['PARTNER']} />}>
        <Route element={<AppLayout />}>
          <Route path="/partner" element={<PartnerDashboardPage />} />
          <Route path="/partner/rides" element={<PartnerRidesPage />} />
          <Route path="/partner/drivers" element={<PartnerDriversPage />} />
          <Route path="/partner/vehicles" element={<PartnerVehiclesPage />} />
          <Route path="/partner/assignments" element={<PartnerAssignmentsPage />} />
          <Route path="/partner/documents" element={<PartnerDocumentsPage />} />
          <Route path="/partner/settings" element={<PartnerSettingsPage />} />
          <Route path="/partner/availability" element={<PartnerAvailabilityPage />} />
        </Route>
      </Route>

      <Route element={<ProtectedRoute allow={['ADMIN']} />}>
        <Route element={<AppLayout />}>
          <Route path="/admin" element={<AdminVerificationPage />} />
          <Route path="/admin/companies" element={<AdminCompaniesPage />} />
          <Route path="/admin/users" element={<AdminUsersPage />} />
          <Route path="/admin/rides" element={<AdminRidesPage />} />
        </Route>
      </Route>

        <Route path="*" element={<RootRedirect />} />
      </Routes>
    </Suspense>
  )
}
