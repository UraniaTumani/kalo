import { Component, type ErrorInfo, type ReactNode } from 'react'
import { AlertTriangle } from 'lucide-react'
import i18n from '@/i18n'
import { UnexpectedResponseError } from '@/lib/api/page'
import { Button, Card, CardBody } from './ui'

interface Props {
  children: ReactNode
  /** Changing this resets the boundary — pass the route so navigating away recovers. */
  resetKey?: string
  /** Centres the message for boundaries that sit outside a page layout. */
  fullscreen?: boolean
}

interface State {
  error: Error | null
}

/**
 * Without this, a throw during render unmounts the whole React tree and the
 * user is left staring at a white page with nothing to act on. Anything that
 * escapes a component now renders as a readable card instead.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidUpdate(previous: Props) {
    if (previous.resetKey !== this.props.resetKey && this.state.error) {
      this.setState({ error: null })
    }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled render error:', error, info.componentStack)
  }

  render() {
    const { error } = this.state

    // Renders children untouched when nothing has thrown, so the boundary adds
    // no wrapper and cannot affect any page's layout.
    if (!error) {
      return this.props.children
    }

    const card = (
      <Card>
        <CardBody className="space-y-3">
          <div className="flex items-start gap-3">
            <span className="mt-0.5 grid size-8 shrink-0 place-items-center rounded-full bg-red-50 text-red-600">
              <AlertTriangle className="size-4" aria-hidden />
            </span>
            <div className="min-w-0">
              <p className="text-sm font-semibold text-ink-900">
                {i18n.t('errors.pageFailed')}
              </p>
              {/*
                A thrown error may carry a translation key rather than a
                sentence, so resolve it here instead of printing the key.
              */}
              <p className="mt-1 text-sm text-ink-600">
                {error instanceof UnexpectedResponseError
                  ? i18n.t(`errors.${error.translationKey}`)
                  : error.message}
              </p>
            </div>
          </div>

          <div className="flex gap-2">
            <Button size="sm" onClick={() => this.setState({ error: null })}>
              {i18n.t('common.tryAgain')}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              onClick={() => window.location.reload()}
            >
              {i18n.t('common.reload')}
            </Button>
          </div>
        </CardBody>
      </Card>
    )

    if (this.props.fullscreen) {
      return (
        <div className="flex min-h-screen items-center justify-center bg-ink-50 p-4">
          <div className="w-full max-w-lg">{card}</div>
        </div>
      )
    }

    return card
  }
}
