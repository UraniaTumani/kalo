import { Logo } from '@/components/Logo'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'

export function AuthShell({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
}) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-ink-50 px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-4 flex justify-end">
          <LanguageSwitcher />
        </div>
        <Link to="/" className="mb-6 flex items-center justify-center" aria-label="MR TAXI">
          <Logo className="h-9" />
        </Link>

        <div className="rounded-xl border border-ink-200/70 bg-white p-6 shadow-sm shadow-ink-900/5">
          <h1 className="text-base font-semibold text-ink-900">{title}</h1>
          {subtitle && <p className="mt-1 text-sm text-ink-500">{subtitle}</p>}
          <div className="mt-5">{children}</div>
        </div>

        {footer && <div className="mt-4 text-center text-sm text-ink-500">{footer}</div>}
      </div>
    </div>
  )
}
