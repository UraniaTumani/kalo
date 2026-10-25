/** Keep invitation credentials out of URLs after the initial document loads. */
export function readInvitationFragment(fragment: string): string | null {
  const params = new URLSearchParams(fragment.replace(/^#/, ''))
  const tokens = params.getAll('invite')
  return tokens.length === 1 && /^[A-Za-z0-9_-]{43}$/.test(tokens[0]) ? tokens[0] : null
}

export function validActivationPassword(password: string): boolean {
  return password.trim().length > 0 && password.length >= 8
    && password.length <= 72 && new TextEncoder().encode(password).length <= 72
}

export const driverInvitationsEnabled = import.meta.env?.VITE_DRIVER_INVITATIONS_ENABLED === 'true'

// Capture before React mounts (including StrictMode's repeated initializers).
// Never store the token in localStorage, sessionStorage, query caches or logs.
let initialToken: string | null = null
if (typeof window !== 'undefined' && window.location.pathname === '/driver/activate') {
  if (driverInvitationsEnabled) initialToken = readInvitationFragment(window.location.hash)
  window.history.replaceState(window.history.state, '', window.location.pathname)
}
export function activationToken(): string | null { return initialToken }
export function clearActivationToken() { initialToken = null }
