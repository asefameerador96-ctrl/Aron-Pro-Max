// Daily Tracking buckets and the 17:00 take-action rule (F-WEB-038, F-WEB-039, F-WEB-028). Pure functions, tested.
import type { Schemas } from "@/contract/types";
import { dhakaHour } from "./server";

export type Bucket = Schemas["DailyTrackingRow"]["bucket"];

/** Display order. `exception` (the route has an approved day exception) is its own bucket, never folded into `not_logged_in`. */
export const BUCKETS: readonly Bucket[] = ["ge_100", "from_90", "from_80", "below_80", "exception", "not_logged_in"];

export function countBuckets(items: readonly { bucket: Bucket }[]): Record<Bucket, number> {
  const out = Object.fromEntries(BUCKETS.map((b) => [b, 0])) as Record<Bucket, number>;
  for (const i of items) out[i.bucket] += 1;
  return out;
}

/** Take-action is offered from 17:00 Dhaka of the business date (cfg.day.take_action_after) and for any earlier date. */
export function canTakeAction(businessDate: string, today: string, now: Date = new Date(), afterHour = 17): boolean {
  if (businessDate < today) return true;
  if (businessDate > today) return false;
  return dhakaHour(now) >= afterHour;
}
