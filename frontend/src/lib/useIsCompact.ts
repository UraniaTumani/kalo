import { useEffect, useState } from 'react'

/** Tailwind's `sm`. Below it a table stops being readable and becomes cards. */
const COMPACT_QUERY = '(max-width: 639px)'

/**
 * True on phone-width screens.
 *
 * Used to render *either* the cards or the table, never both. Hiding one with
 * `sm:hidden` and the other with `hidden sm:block` is the usual trick and it
 * looks identical, but it leaves every row in the document twice: a screen
 * reader walks both copies, the DOM doubles for a long list, and any query for
 * a row can land on the invisible one — which is exactly how two browser tests
 * started failing against a page that looked perfect.
 */
export function useIsCompact(): boolean {
  const [compact, setCompact] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(COMPACT_QUERY).matches,
  )

  useEffect(() => {
    const query = window.matchMedia(COMPACT_QUERY)
    const update = (event: MediaQueryListEvent) => setCompact(event.matches)

    setCompact(query.matches)
    query.addEventListener('change', update)

    return () => query.removeEventListener('change', update)
  }, [])

  return compact
}
