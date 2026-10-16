/**
 * F23: one shared interval for the partner's live booking surfaces.
 *
 * No push channel exists yet. Poll only while the tab is visible; returning
 * to it triggers an immediate refetch. Do not use background polling here:
 * an idle fleet of partner tabs must not generate continuous API traffic.
 */
export const PARTNER_LIVE_REFRESH_MS = 5_000
