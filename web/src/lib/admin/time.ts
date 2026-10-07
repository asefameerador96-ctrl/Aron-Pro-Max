// Wall-clock reads live here so components stay pure (react-hooks/purity) and tests can pass a fixed time.
export const serverNowMs = (): number => Date.now();

/** A real calendar date in YYYY-MM-DD form (2026-02-30 and 2026-13-01 are not). */
export function isRealDate(s: string | undefined): s is string {
  if (!s || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return false;
  const d = new Date(`${s}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s;
}
