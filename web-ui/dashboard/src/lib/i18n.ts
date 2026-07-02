/*
 * Brand + i18n (FE-3 — name-light + D-FE-9 i18n-readiness).
 * ---------------------------------------------------------------------------
 * 1) BRAND.productName is the SINGLE source of truth for the product name. The rename (W-11)
 *    is unratified, so the value stays "HomeSynapse" — but every user-facing surface references
 *    this token, so the eventual swap is one line. NEVER hardcode the product name (name-light).
 *
 * 2) Copy is a KEYED catalog, not inline strings — the i18n-readiness seam. V1 ships English
 *    only. To add a locale: provide another Messages catalog and resolve `locale` from
 *    navigator.language / a stored preference; the same seam is where RTL direction and Intl
 *    number/date/relative-time formatting attach. Keys are stable; only values localize. (Most
 *    enum-label copy still lives in format.ts; migrating those maps behind t() is the same
 *    mechanical pattern and can follow.)
 */

export const BRAND = {
  /** The product name. Unratified rename (W-11) flips this one value; nothing else changes. */
  productName: 'HomeSynapse',
} as const;

/** V1 locale. Reserved seam: resolve from navigator/stored preference when locales are added. */
export const locale = 'en' as const;

const en = {
  'auth.tokenHelp': `Paste the pairing token from your ${BRAND.productName} device. You’ll find it in`,
  'boot.startingBody': `${BRAND.productName} is catching up to live — this takes a moment after a restart.`,
  'devices.lede': `Everything ${BRAND.productName} can see in your home.`,
  'health.live': `${BRAND.productName} is up to date and processing events in real time.`,
  'overview.live': `${BRAND.productName} is live and watching your home in real time.`,
  'overview.catchingUp': `${BRAND.productName} is catching up after a restart — this only takes a moment.`,
  'hero.permanence': `This explanation is rebuilt from ${BRAND.productName}’s permanent activity log — it is never deleted, so the run you need is always here.`,
  'origin.external.phrase': `outside ${BRAND.productName}`,
  // The M7.5c gap, rendered honestly (FE-1): the hub doesn't serve this read yet.
  'events.notServedYet.title': 'Your hub doesn’t share the activity feed yet.',
  'events.notServedYet.hint': `Everything else on this dashboard is live. The feed arrives with a ${BRAND.productName} update — nothing is wrong.`,
} as const;

type Messages = typeof en;
export type MessageKey = keyof Messages;

const catalogs: Record<typeof locale, Messages> = { en };

/** Resolve a message key to text in the active locale. */
export function t(key: MessageKey): string {
  return catalogs[locale][key];
}
