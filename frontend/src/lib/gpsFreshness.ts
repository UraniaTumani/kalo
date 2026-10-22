/** A browser can keep reporting its last fix even when GPS updates stop.
 * Do not refresh the server timestamp using an old or invalid fix.
 */
export function isFreshGpsFix(timestamp: number, now: number): boolean {
  const age = now - timestamp
  return Number.isFinite(timestamp) && Number.isFinite(now) &&
    age <= 30_000 && age >= -5_000
}
