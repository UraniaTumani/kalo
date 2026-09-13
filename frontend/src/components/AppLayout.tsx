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
import { useTranslation } from 'react-i18next'
import { useAuth } from '@/auth/AuthContext'
import { cn } from '@/lib/utils'
import { Button } from './ui'
import { ErrorBoundary } from './ErrorBoundary'
import { LanguageSwitcher } from './LanguageSwitcher'

interface NavItem {
  to: string
  /** Translation key, resolved at render so switching language is instant. */
  labelKey: string
  icon: LucideIcon
  end?: boolean
}

const customerNav: NavItem[] = [
  { to: '/ride', labelKey: 'nav.book', icon: MapPin, end: true },
  { to: '/ride/current', labelKey: 'nav.currentRide', icon: Car },
  { to: '/ride/history', labelKey: 'nav.history', icon: History },
]

const partnerNav: NavItem[] = [
  { to: '/partner', labelKey: 'nav.dashboard', icon: Gauge, end: true },
  { to: '/partner/rides', labelKey: 'nav.rides', icon: ClipboardList },
  { to: '/partner/drivers', labelKey: 'nav.drivers', icon: Users },
  { to: '/partner/vehicles', labelKey: 'nav.vehicles', icon: Car },
  { to: '/partner/assignments', labelKey: 'nav.assignments', icon: MapPin },
  { to: '/partner/documents', labelKey: 'nav.documents', icon: FileText },
  { to: '/partner/settings', labelKey: 'nav.settings', icon: Settings },
  { to: '/partner/availability', labelKey: 'nav.availability', icon: Clock },
]

const adminNav: NavItem[] = [
  { to: '/admin', labelKey: 'nav.verification', icon: ShieldCheck, end: true },
  { to: '/admin/companies', labelKey: 'nav.companies', icon: Building2 },
  { to: '/admin/users', labelKey: 'nav.users', icon: Users },
  { to: '/admin/rides', labelKey: 'nav.rides', icon: ClipboardList },
]

const navByRole = {
  CUSTOMER: customerNav,
  PARTNER: partnerNav,
  ADMIN: adminNav,
}

export function AppLayout() {
  const { user, role, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const { t } = useTranslation()

  if (!user || !role) return null

  // Every role gets the profile entry, always last.
  const items: NavItem[] = [
    ...navByRole[role],
    { to: '/profile', labelKey: 'nav.profile', icon: User },
  ]

  function signOut() {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="flex min-h-screen flex-col lg:flex-row">
      {/*
        A rail on desktop, a scrolling strip of tabs on mobile. The strip keeps
        the destinations visible rather than hiding them behind a menu button —
        a partner watching for incoming rides should not have to open anything.
      */}
      <aside className="sticky top-0 z-30 shrink-0 border-b border-ink-200/70 bg-white/95 backdrop-blur lg:static lg:flex lg:w-60 lg:flex-col lg:border-b-0 lg:border-r lg:bg-white">
        <div className="flex items-center gap-2.5 px-5 py-4">
          <span
            aria-hidden
            className="grid size-9 place-items-center rounded-xl bg-brand-400 text-sm font-black text-ink-950"
          >
            K
          </span>
          <div className="min-w-0">
            <p className="text-sm font-bold tracking-tight text-ink-900">KALO</p>
            <p className="truncate text-[11px] font-medium text-ink-500">{t(`roles.${role}`)}</p>
          </div>
        </div>

        <nav className="flex gap-1 overflow-x-auto px-3 pb-3 lg:mt-1 lg:flex-col lg:overflow-visible lg:pb-0">
          {items.map(({ to, labelKey, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  'group relative flex shrink-0 items-center gap-2.5 rounded-xl px-3 py-2 text-sm font-medium transition-colors',
                  'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-600',
                  isActive
                    ? 'bg-brand-50 text-brand-900'
                    : 'text-ink-600 hover:bg-ink-100 hover:text-ink-900',
                )
              }
            >
              {({ isActive }) => (
                <>
                  {/*
                    A bar on the active item rather than colour alone: on the
                    desktop rail it reads instantly, and it is one more signal
                    for anybody who does not separate these hues easily.
                  */}
                  <span
                    aria-hidden
                    className={cn(
                      'absolute left-0 top-1/2 hidden h-5 w-1 -translate-y-1/2 rounded-r-full bg-brand-500 lg:block',
                      isActive ? 'opacity-100' : 'opacity-0',
                    )}
                  />
                  <Icon
                    className={cn('size-4 shrink-0', isActive ? 'text-brand-700' : 'text-ink-400')}
                    aria-hidden
                  />
                  {t(labelKey)}
                </>
              )}
            </NavLink>
          ))}
        </nav>

        <div className="mt-auto hidden border-t border-ink-100 px-4 py-4 lg:block">
          <div className="flex items-center gap-2.5">
            <span
              aria-hidden
              className="grid size-8 shrink-0 place-items-center rounded-full bg-ink-100 text-xs font-bold text-ink-600"
            >
              {user.firstName.charAt(0)}
              {user.lastName.charAt(0)}
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold text-ink-800">
                {user.firstName} {user.lastName}
              </p>
              <p className="tnum truncate text-xs text-ink-500">{user.phone}</p>
            </div>
          </div>

          <div className="mt-3 flex items-center justify-between gap-2">
            <Button variant="ghost" size="sm" className="-ml-1.5" onClick={signOut}>
              <LogOut className="size-3.5" aria-hidden />
              {t('common.signOut')}
            </Button>
            <LanguageSwitcher />
          </div>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between gap-3 border-b border-ink-200/70 bg-white px-4 py-2.5 lg:hidden">
          <p className="truncate text-sm font-semibold text-ink-800">
            {user.firstName} {user.lastName}
          </p>
          <div className="flex items-center gap-1.5">
            <LanguageSwitcher />
            <Button variant="ghost" size="sm" onClick={signOut}>
              <LogOut className="size-3.5" aria-hidden />
              <span className="sr-only sm:not-sr-only">{t('common.signOut')}</span>
            </Button>
          </div>
        </header>

        {/*
          Inside the layout on purpose: a page that fails still leaves the
          navigation usable, and the key resets the boundary on route change so
          navigating away recovers without a reload.

          Capped at 5xl rather than 6xl — a table stretched across a 27-inch
          monitor is harder to read, not easier.
        */}
        <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-6 sm:px-6 lg:py-8">
          <ErrorBoundary resetKey={location.pathname}>
            <Outlet />
          </ErrorBoundary>
        </main>
      </div>
    </div>
  )
}

/**
 * The first thing on every screen: what this page is, and the one action it
 * most wants. Bigger and better spaced than before, so a page opens with a
 * clear title rather than running straight into a card.
 */
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
    <div className="mb-6 flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
      <div className="min-w-0">
        <h1 className="text-xl font-bold tracking-tight text-ink-900 sm:text-2xl">{title}</h1>
        {description && (
          <p className="mt-1 max-w-2xl text-sm leading-relaxed text-ink-500">{description}</p>
        )}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  )
}
