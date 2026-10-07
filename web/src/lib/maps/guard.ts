// Daily cost guard for Google Maps JavaScript loads (N-047, docs/28: pilot-size budget). Every map mount asks the BFF first; the
// BFF counts loads per Dhaka business day and refuses above the cap, so a runaway tab or a leaked page cannot run up the bill.
// The counter is per server instance (pilot: one instance). The final account moves it to the database; the cap is a setting.
import { businessDate } from "@/lib/i18n";

export const DEFAULT_DAILY_CAP = 2000;

export class MapsGuard {
  private day = "";
  private used = 0;
  constructor(private readonly cap: number) {}

  /** Count one load. Returns whether it is allowed and the counters (never negative, resets at the Dhaka day change). */
  take(now: Date = new Date()): { allowed: boolean; used: number; cap: number } {
    const day = businessDate(now);
    if (day !== this.day) {
      this.day = day;
      this.used = 0;
    }
    if (this.used >= this.cap) return { allowed: false, used: this.used, cap: this.cap };
    this.used += 1;
    return { allowed: true, used: this.used, cap: this.cap };
  }
}

export function capFromEnv(env: Record<string, string | undefined> = process.env): number {
  if (!env.MAPS_DAILY_CAP?.trim()) return DEFAULT_DAILY_CAP;
  const n = Number(env.MAPS_DAILY_CAP);
  return Number.isInteger(n) && n >= 0 ? n : DEFAULT_DAILY_CAP;
}

export function mapsKey(env: Record<string, string | undefined> = process.env): string | null {
  return env.MAPS_WEB_KEY || null; // server-side only: a NEXT_PUBLIC key would be inlined into public client JS
}

let shared: MapsGuard | null = null;
export function sharedGuard(): MapsGuard {
  shared ??= new MapsGuard(capFromEnv());
  return shared;
}
