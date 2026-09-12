import type { Page } from './types'

/**
 * Thrown when an endpoint answers with a shape the UI cannot render. Carrying
 * a specific message means the error boundary can tell the user what actually
 * went wrong instead of showing a generic failure.
 */
export class UnexpectedResponseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'UnexpectedResponseError'
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
 *
 * This turns that into a named error the boundary can explain.
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
    throw new UnexpectedResponseError(
      'The server returned a plain list where a paged result was expected. ' +
        'The backend is probably running an older build than this frontend — ' +
        'restart it from the current main branch.',
    )
  }

  if (!Array.isArray((data as Page<T>).content)) {
    throw new UnexpectedResponseError(
      'The server returned an unexpected response for this list.',
    )
  }

  return {
    rows: data.content,
    page: data,
    isEmpty: data.content.length === 0,
  }
}
