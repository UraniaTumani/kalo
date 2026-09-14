import type {
  ButtonHTMLAttributes,
  InputHTMLAttributes,
  ReactNode,
  Ref,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react'
import { Search } from 'lucide-react'
import { cn } from '@/lib/utils'

/* ---------------------------------------------------------------- Button */

/*
 * Five variants, and the hierarchy is the point: exactly one primary per view,
 * secondary for everything reversible, ghost for row actions that would
 * otherwise turn a table into a wall of buttons.
 *
 * Primary is amber with charcoal text rather than white on amber. It reads as a
 * taxi sign, which is the association this product wants — and white on amber-600
 * is 3.6:1, which is not enough for a label people must read quickly.
 */
type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'success'
type ButtonSize = 'sm' | 'md' | 'lg'

const buttonVariants: Record<ButtonVariant, string> = {
  primary:
    'bg-brand-400 text-ink-950 shadow-sm hover:bg-brand-300 active:bg-brand-500 focus-visible:outline-brand-600',
  secondary:
    'bg-white text-ink-800 ring-1 ring-inset ring-ink-200 hover:bg-ink-50 hover:ring-ink-300 focus-visible:outline-ink-400',
  ghost: 'text-ink-600 hover:bg-ink-100 hover:text-ink-900 focus-visible:outline-ink-400',
  danger: 'bg-bad-500 text-white hover:bg-bad-600 active:bg-bad-700 focus-visible:outline-bad-500',
  success: 'bg-good-500 text-white hover:bg-good-600 focus-visible:outline-good-500',
}

const buttonSizes: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-xs gap-1.5',
  md: 'h-10 px-4 text-sm gap-2',
  lg: 'h-12 px-6 text-base gap-2',
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  loading?: boolean
  /** React 19 passes ref as an ordinary prop; ConfirmDialog focuses Cancel. */
  ref?: Ref<HTMLButtonElement>
}

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  disabled,
  className,
  children,
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      disabled={disabled || loading}
      className={cn(
        'inline-flex items-center justify-center rounded-xl font-semibold transition-colors',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2',
        'disabled:cursor-not-allowed disabled:opacity-45',
        buttonVariants[variant],
        buttonSizes[size],
        className,
      )}
    >
      {loading && (
        <span
          aria-hidden
          className="size-3.5 animate-spin rounded-full border-2 border-current border-t-transparent"
        />
      )}
      {children}
    </button>
  )
}

/** Named exports so a page reads its own intent. */
export const PrimaryButton = (props: ButtonProps) => <Button {...props} variant="primary" />
export const SecondaryButton = (props: ButtonProps) => <Button {...props} variant="secondary" />
export const DangerButton = (props: ButtonProps) => <Button {...props} variant="danger" />

/* ------------------------------------------------------------------ Card */

/*
 * One border, one radius, one shadow. The old look put a ring around every
 * grouping, which turned a page into a grid of boxes and made nothing stand out.
 */
export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <div
      className={cn(
        'rounded-2xl border border-ink-200/60 bg-white shadow-[var(--shadow-card)]',
        className,
      )}
    >
      {children}
    </div>
  )
}

export function CardHeader({
  title,
  description,
  action,
}: {
  title: ReactNode
  description?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-ink-100 px-5 py-4">
      <div className="min-w-0">
        <h2 className="text-sm font-semibold tracking-tight text-ink-900">{title}</h2>
        {description && <p className="mt-0.5 text-xs leading-relaxed text-ink-500">{description}</p>}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  )
}

export function CardBody({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('px-5 py-4', className)}>{children}</div>
}

/**
 * A card with its heading built in — the shape most screens actually want, and
 * the reason so many pages had repeated the same header markup.
 */
export function SectionCard({
  title,
  description,
  action,
  bodyClassName,
  className,
  children,
}: {
  title: ReactNode
  description?: ReactNode
  action?: ReactNode
  bodyClassName?: string
  className?: string
  children: ReactNode
}) {
  return (
    <Card className={className}>
      <CardHeader title={title} description={description} action={action} />
      <CardBody className={bodyClassName}>{children}</CardBody>
    </Card>
  )
}

/* --------------------------------------------------------------- StatCard */

type StatTone = 'neutral' | 'good' | 'warn' | 'bad' | 'brand'

const statTones: Record<StatTone, string> = {
  neutral: 'text-ink-900',
  good: 'text-good-600',
  warn: 'text-warn-600',
  bad: 'text-bad-600',
  brand: 'text-brand-700',
}

/**
 * One figure, said plainly. Used across the partner dashboard, where the
 * question is always "how many, and is that a problem".
 */
export function StatCard({
  label,
  value,
  hint,
  icon,
  tone = 'neutral',
}: {
  label: string
  value: ReactNode
  hint?: string
  icon?: ReactNode
  tone?: StatTone
}) {
  return (
    <div className="rounded-2xl border border-ink-200/60 bg-white px-4 py-3.5 shadow-[var(--shadow-card)]">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium uppercase tracking-wide text-ink-500">{label}</span>
        {icon && <span className="text-ink-300">{icon}</span>}
      </div>
      <p className={cn('tnum mt-1.5 text-2xl font-semibold leading-none', statTones[tone])}>
        {value}
      </p>
      {hint && <p className="mt-1 text-xs text-ink-500">{hint}</p>}
    </div>
  )
}

/* ----------------------------------------------------------------- Field */

export function Field({
  label,
  error,
  hint,
  required,
  children,
}: {
  label: string
  error?: string
  hint?: string
  required?: boolean
  children: ReactNode
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-semibold text-ink-700">
        {label}
        {required && <span className="ml-0.5 text-bad-500">*</span>}
      </span>
      {children}
      {/* Hint and error share one slot, so a form does not jump as it validates. */}
      {hint && !error && <span className="mt-1 block text-xs text-ink-400">{hint}</span>}
      {error && <span className="mt-1 block text-xs font-medium text-bad-600">{error}</span>}
    </label>
  )
}

/** Groups related fields, so a long form reads as a few decisions, not twenty. */
export function FormSection({
  title,
  description,
  children,
  className,
}: {
  title?: string
  description?: string
  children: ReactNode
  className?: string
}) {
  return (
    <div className={cn('space-y-3', className)}>
      {title && (
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-500">{title}</h3>
          {description && <p className="mt-0.5 text-xs text-ink-400">{description}</p>}
        </div>
      )}
      <div className="space-y-3">{children}</div>
    </div>
  )
}

const controlStyles =
  'w-full rounded-xl border-0 bg-white px-3.5 text-sm text-ink-900 shadow-sm ' +
  'ring-1 ring-inset ring-ink-200 placeholder:text-ink-400 transition ' +
  'focus:ring-2 focus:ring-inset focus:ring-brand-500 ' +
  'disabled:cursor-not-allowed disabled:bg-ink-50 disabled:text-ink-400'

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cn(controlStyles, 'h-10', className)} />
}

export function Textarea({ className, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} className={cn(controlStyles, 'resize-y py-2.5', className)} />
}

export function Select({ className, children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select {...props} className={cn(controlStyles, 'h-10 pr-8', className)}>
      {children}
    </select>
  )
}

/** A search box that looks like one, rather than a bare text field. */
export function SearchInput({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <div className={cn('relative', className)}>
      <Search
        aria-hidden
        className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-400"
      />
      <input {...props} type="search" className={cn(controlStyles, 'h-10 pl-9')} />
    </div>
  )
}

/** Holds the controls that narrow a list, so they never look like page content. */
export function FilterBar({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        'flex flex-wrap items-end gap-3 rounded-2xl border border-ink-200/60 bg-white px-4 py-3',
        className,
      )}
    >
      {children}
    </div>
  )
}

/* ----------------------------------------------------------------- Badge */

type BadgeTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'brand'

const badgeTones: Record<BadgeTone, string> = {
  neutral: 'bg-ink-100 text-ink-700 ring-ink-200',
  info: 'bg-brand-50 text-brand-800 ring-brand-200',
  brand: 'bg-brand-100 text-brand-900 ring-brand-300',
  success: 'bg-good-50 text-good-700 ring-good-100',
  warning: 'bg-warn-50 text-warn-600 ring-warn-100',
  danger: 'bg-bad-50 text-bad-700 ring-bad-100',
}

export function Badge({
  tone = 'neutral',
  dot = false,
  children,
}: {
  tone?: BadgeTone
  /** A filled dot, for states somebody scans a column of. */
  dot?: boolean
  children: ReactNode
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset',
        badgeTones[tone],
      )}
    >
      {dot && <span aria-hidden className="size-1.5 rounded-full bg-current opacity-70" />}
      {children}
    </span>
  )
}

/* ----------------------------------------------------------------- Alert */

export function Alert({
  tone = 'danger',
  title,
  children,
}: {
  tone?: BadgeTone
  title?: string
  children?: ReactNode
}) {
  return (
    <div
      className={cn('rounded-xl px-4 py-3 text-sm ring-1 ring-inset', badgeTones[tone])}
      role={tone === 'danger' ? 'alert' : undefined}
    >
      {title && <p className="font-semibold">{title}</p>}
      {children && <div className={cn(title && 'mt-0.5', 'leading-relaxed')}>{children}</div>}
    </div>
  )
}

/* ------------------------------------------------------------ EmptyState */

/**
 * An empty list is a moment to say what would fill it, not a blank panel. The
 * icon is optional because an empty table inside a card does not need one.
 */
export function EmptyState({
  title,
  description,
  action,
  icon,
}: {
  title: string
  description?: string
  action?: ReactNode
  icon?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-14 text-center">
      {icon && (
        <div className="mb-1 flex size-11 items-center justify-center rounded-full bg-ink-100 text-ink-400">
          {icon}
        </div>
      )}
      <p className="text-sm font-semibold text-ink-800">{title}</p>
      {description && (
        <p className="max-w-sm text-xs leading-relaxed text-ink-500">{description}</p>
      )}
      {action && <div className="mt-3">{action}</div>}
    </div>
  )
}

/* ----------------------------------------------------------- PageSkeleton */

/**
 * Shown while a screen loads. Blocks roughly where the content will be, so the
 * page does not jump when it arrives — a spinner in the middle of an empty page
 * tells somebody nothing about what is coming.
 */
export function PageSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="space-y-3" role="status" aria-busy="true">
      <div className="h-24 animate-pulse rounded-2xl bg-ink-100" />
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="h-14 animate-pulse rounded-xl bg-ink-100/70" />
      ))}
    </div>
  )
}

/* ----------------------------------------------------------------- Table */

/**
 * Scrolls sideways inside its own box, so a wide table never takes the page with
 * it.
 *
 * `min-w-0` is the part that actually does the work: a grid or flex item is
 * `min-width: auto` by default and refuses to shrink below its content, so
 * without it the table's own minimum width pushes its whole column past the edge
 * of a phone and the body scrolls sideways.
 *
 * There is deliberately no negative margin. One assumed a parent with matching
 * padding — true inside CardBody, false for the several tables that sit straight
 * in a Card, where it pulled them twenty pixels past each edge and was the cause
 * of the overflow this comment exists to prevent returning.
 */
export function Table({ children }: { children: ReactNode }) {
  return (
    <div className="w-full min-w-0 overflow-x-auto">
      <table className="w-full min-w-[34rem] border-collapse text-left text-sm">{children}</table>
    </div>
  )
}

export function Th({ children, className }: { children?: ReactNode; className?: string }) {
  return (
    <th
      className={cn(
        'whitespace-nowrap border-b border-ink-200 px-3 py-2.5 text-xs font-semibold uppercase tracking-wide text-ink-500',
        className,
      )}
    >
      {children}
    </th>
  )
}

export function Td({ children, className }: { children?: ReactNode; className?: string }) {
  return (
    <td className={cn('border-b border-ink-100 px-3 py-3.5 align-middle text-ink-800', className)}>
      {children}
    </td>
  )
}

/** Row actions, kept to the right and spaced so they never crowd the content. */
export function RowActions({ children }: { children: ReactNode }) {
  return <div className="flex items-center justify-end gap-1">{children}</div>
}
