import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import type { LucideIcon } from 'lucide-react'
import {
  Building2,
  Car,
  ClipboardList,
  Clock,
  FileText,
  Gauge,
  History,
  LogOut,
  MapPin,
  Settings,
  ShieldCheck,
  User,
  Users,
} from 'lucide-react'
import { useAuth } from '@/auth/AuthContext'
import { cn } from '@/lib/utils'
import { Button } from './ui'
import { ErrorBoundary } from './ErrorBoundary'

interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  end?: boolean
}

const customerNav: NavItem[] = [
  { to: '/ride', label: 'Book a taxi', icon: MapPin, end: true },
  { to: '/ride/current', label: 'Current ride', icon: Car },
  { to: '/ride/history', label: 'History', icon: History },
]

const partnerNav: NavItem[] = [
  { to: '/partner', label: 'Dashboard', icon: Gauge, end: true },
  { to: '/partner/rides', label: 'Rides', icon: ClipboardList },
  { to: '/partner/drivers', label: 'Drivers', icon: Users },
  { to: '/partner/vehicles', label: 'Vehicles', icon: Car },
  { to: '/partner/assignments', label: 'Assignments', icon: MapPin },
  { to: '/partner/documents', label: 'Documents', icon: FileText },
  { to: '/partner/settings', label: 'Settings', icon: Settings },
  { to: '/partner/availability', label: 'Availability', icon: Clock },
]

const adminNav: NavItem[] = [
  { to: '/admin', label: 'Verification', icon: ShieldCheck, end: true },
  { to: '/admin/companies', label: 'Companies', icon: Building2 },
  { to: '/admin/users', label: 'Users', icon: Users },
  { to: '/admin/rides', label: 'Rides', icon: ClipboardList },
]

const navByRole = {
  CUSTOMER: customerNav,
  PARTNER: partnerNav,
  ADMIN: adminNav,
}

const roleLabel = {
  CUSTOMER: 'Passenger',
  PARTNER: 'Taxi company',
  ADMIN: 'Administrator',
}

export function AppLayout() {
  const { user, role, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  if (!user || !role) return null

  // Every role gets the profile entry, always last.
  const items: NavItem[] = [
    ...navByRole[role],
    { to: '/profile', label: 'Profile', icon: User },
  ]

  return (
    <div className="flex min-h-screen flex-col lg:flex-row">
      <aside className="flex shrink-0 flex-col border-b border-ink-200 bg-white lg:w-60 lg:border-r lg:border-b-0">
        <div className="flex items-center gap-2 px-5 py-4">
          <span className="grid size-8 place-items-center rounded-lg bg-brand-600 text-sm font-bold text-white">
            K
          </span>
          <div>
            <p className="text-sm font-semibold text-ink-900">KALO</p>
            <p className="text-[11px] text-ink-500">{roleLabel[role]}</p>
          </div>
        </div>

        <nav className="flex gap-1 overflow-x-auto px-3 pb-3 lg:flex-col lg:overflow-visible lg:pb-0">
          {items.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  'flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm transition',
                  isActive
                    ? 'bg-brand-50 font-medium text-brand-800'
                    : 'text-ink-600 hover:bg-ink-100 hover:text-ink-900',
                )
              }
            >
              <Icon className="size-4" aria-hidden />
              {label}
            </NavLink>
          ))}
        </nav>

        <div className="mt-auto hidden border-t border-ink-200 px-5 py-4 lg:block">
          <p className="truncate text-sm font-medium text-ink-800">
            {user.firstName} {user.lastName}
          </p>
          <p className="truncate text-xs text-ink-500">{user.phone}</p>
          <Button
            variant="ghost"
            size="sm"
            className="mt-2 -ml-2"
            onClick={() => {
              logout()
              navigate('/login', { replace: true })
            }}
          >
            <LogOut className="size-3.5" aria-hidden />
            Sign out
          </Button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between gap-4 border-b border-ink-200 bg-white px-5 py-3 lg:hidden">
          <p className="truncate text-sm font-medium text-ink-800">
            {user.firstName} {user.lastName}
          </p>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              logout()
              navigate('/login', { replace: true })
            }}
          >
            <LogOut className="size-3.5" aria-hidden />
            Sign out
          </Button>
        </header>

        {/*
          Inside the layout on purpose: a page that fails still leaves the
          navigation usable, and the key resets the boundary on route change so
          navigating away recovers without a reload.
        */}
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-6">
          <ErrorBoundary resetKey={location.pathname}>
            <Outlet />
          </ErrorBoundary>
        </main>
      </div>
    </div>
  )
}

export function PageHeader({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: React.ReactNode
}) {
  return (
    <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-lg font-semibold text-ink-900">{title}</h1>
        {description && <p className="mt-0.5 text-sm text-ink-500">{description}</p>}
      </div>
      {action}
    </div>
  )
}
