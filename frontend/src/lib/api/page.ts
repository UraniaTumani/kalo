import type { Page } from './types'

/**
 * Thrown when an endpoint answers with a shape the UI cannot render.
 *
 * Carries a translation key rather than a finished sentence so the message
 * follows the selected language; see useErrorMessage.
 */
export class UnexpectedResponseError extends Error {
  readonly translationKey: string

  constructor(translationKey: string) {
    super(translationKey)
    this.name = 'UnexpectedResponseError'
    this.translationKey = translationKey
  }
}

/**
 * Reads a paged payload defensively.
 *
 * Every list endpoint in this app returns a Spring `Page`. Reaching straight
 * for `data.content.map(...)` means that if an endpoint ever answers with
 * something else — most likely a server running an older build, from before
 * these lists were paginated — the component throws
 * `undefined is not a function` and the whole page goes blank.
 */
export function readPage<T>(data: Page<T> | undefined): {
  rows: T[]
  page: Page<T> | undefined
  isEmpty: boolean
} {
  if (data === undefined) {
    return { rows: [], page: undefined, isEmpty: false }
  }

  if (Array.isArray(data)) {
    throw new UnexpectedResponseError('staleBackend')
  }

  if (!Array.isArray((data as Page<T>).content)) {
    throw new UnexpectedResponseError('unexpectedShape')
  }

  return {
    rows: data.content,
    page: data,
    isEmpty: data.content.length === 0,
  }
}
