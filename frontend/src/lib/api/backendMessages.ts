/**
 * Translation keys for the backend sentences a customer can actually reach.
 *
 * THE PROBLEM THIS ADDRESSES, AND THE PART IT DOES NOT. The API answers a
 * refused business rule with a finished English sentence, and `useErrorMessage`
 * shows it as-is because no frontend catalogue can cover every rule the server
 * enforces. That is a documented trade-off, not an oversight — but the scale
 * was the new information: `InvalidOperationException` and `ConflictException`
 * are thrown in roughly 126 places, so "the exception" is most of the
 * product's rule vocabulary, and the pilot is Albanian-speaking.
 *
 * Three ways out were weighed (F39). Stable error codes from the backend are
 * the only approach that scales to all 126, and they need a backend change.
 * This is the second: translate the subset a customer can actually trigger,
 * which is far smaller and ships now. It does not preclude the codes; when
 * they arrive this table is deleted rather than extended.
 *
 * WHAT IS IN IT. Only messages reachable from a customer's own screens —
 * searching, choosing a company, cancelling, viewing the current ride. Partner
 * and admin screens keep the English fallthrough deliberately: they are a
 * smaller, more technical audience, and padding this table with rules a
 * customer cannot hit would make it look more complete than it is.
 *
 * MATCHING IS EXACT, against the sentence as the server sends it, because
 * these strings are a wire contract this file does not own. A message whose
 * wording changes stops matching and falls through to the English — the same
 * behaviour as today, which is why the fallthrough stays. The alternative,
 * fuzzy matching, would translate the wrong rule into the customer's language
 * and read as authoritative.
 */

/** Messages matched whole. */
const EXACT: Record<string, string> = {
  /* Searching. */
  'Customer account is not active': 'accountInactive',
  'Customer already has an active ride': 'activeRideExists',
  'Pickup and destination cannot be the same': 'samePickupAndDestination',
  'Only customers can search for taxis': 'customersOnly',
  'Only customers can perform this operation': 'customersOnly',

  /* Choosing a company. */
  'Ride request has expired': 'requestExpired',
  'Ride request is not available for taxi selection': 'requestNotSelectable',
  'Ride request not found': 'requestNotFound',
  'Taxi offer not found': 'offerNotFound',
  'Taxi company is no longer available': 'companyUnavailable',
  'Taxi company is not currently accepting bookings': 'companyNotAcceptingBookings',
  'Taxi company is not currently available for this pickup location': 'companyOutOfArea',
  'Taxi company does not have an available payment method': 'companyNoPaymentMethod',
  'This taxi company has already been tried for this ride request': 'companyAlreadyTried',

  /* The ride itself. */
  'No active ride found': 'noActiveRide',
  'Ride not found': 'rideNotFound',
  'Ride is no longer active': 'rideNotActive',

  /* Cancelling. */
  'Ride cannot be cancelled after it has started': 'cancelAfterStart',
  'Completed ride cannot be cancelled': 'cancelCompleted',
  'Ride is already cancelled': 'alreadyCancelled',
  'Declined ride cannot be cancelled': 'cancelDeclined',
}

/**
 * Messages that end in a value — a date, a name — and so cannot be matched
 * whole.
 *
 * The translated sentence deliberately drops that tail rather than trying to
 * reproduce it: the date is the company's licence expiry, which is the
 * operator's problem to fix and not something the customer can act on. They
 * need to know to choose someone else.
 */
const PREFIXES: Array<[string, string]> = [
  ["This company's taxi license expired on ", 'companyLicenceExpired'],
  ['This company has no taxi license expiry date on record.', 'companyLicenceMissing'],
]

/**
 * The `errors.backend.*` key for a server message, or null when there is none.
 *
 * Null is the ordinary answer for most of the 126 and means "show the server's
 * own sentence", which is what happened for all of them before this existed.
 */
export function backendMessageKey(message: string | undefined | null): string | null {
  if (!message) return null

  const exact = EXACT[message]
  if (exact) return `errors.backend.${exact}`

  for (const [prefix, key] of PREFIXES) {
    if (message.startsWith(prefix)) return `errors.backend.${key}`
  }

  return null
}

/** Exported for the test that keeps this table and the catalogues in step. */
export const BACKEND_MESSAGE_KEYS = [
  ...Object.values(EXACT),
  ...PREFIXES.map(([, key]) => key),
]
