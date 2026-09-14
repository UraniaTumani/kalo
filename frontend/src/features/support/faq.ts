import type { UserRole } from '@/lib/api/types'

/**
 * The FAQ, as translation keys rather than prose.
 *
 * Each entry resolves to `support.faq.<id>.q` and `.a`, so the Albanian and
 * English versions stay in the locale files with every other string instead of
 * being duplicated in a component.
 *
 * `roles` is what a question is *for*, not what it is about. A customer asking
 * why a price changed and a partner asking the same thing need different
 * answers, so each side gets its own entry rather than one hedged answer.
 */
export interface FaqEntry {
  id: string
  roles: UserRole[]
}

export const FAQ: FaqEntry[] = [
  /* Customer */
  { id: 'howBooking', roles: ['CUSTOMER'] },
  { id: 'finalPrice', roles: ['CUSTOMER'] },
  { id: 'payment', roles: ['CUSTOMER'] },
  { id: 'cancelRide', roles: ['CUSTOMER'] },
  { id: 'noDriver', roles: ['CUSTOMER'] },
  { id: 'lostItem', roles: ['CUSTOMER'] },

  /* Partner */
  { id: 'verification', roles: ['PARTNER'] },
  { id: 'assignDriver', roles: ['PARTNER'] },
  { id: 'operatingHours', roles: ['PARTNER'] },
  { id: 'expiredPaperwork', roles: ['PARTNER'] },
  { id: 'missedRequests', roles: ['PARTNER'] },

  /* Both */
  { id: 'changeLanguage', roles: ['CUSTOMER', 'PARTNER'] },
  { id: 'accountSecurity', roles: ['CUSTOMER', 'PARTNER'] },
]

export function faqFor(role: UserRole): FaqEntry[] {
  return FAQ.filter((entry) => entry.roles.includes(role))
}
