// Server-side reads of the loyalty screens (portal-loyalty-web), typed from the generated contract 1.13.0 (a drift fails tsc). Every
// read goes through the session token; the API derives the reach from it. getProgramme and getGift serve the detail reads.
import { apiClient, outcome } from "@/lib/api/client";
import type { Enrolment, Gift, Programme, ProgrammeVersion } from "./loyalty";

export type Page<T> = { items: T[]; next: string | null };
export type Loaded<T> = { ok: true; data: T } | { ok: false; offline: boolean; status: number };
const fail = (status: number): { ok: false; offline: boolean; status: number } => ({ ok: false, offline: status >= 500 || status === 0, status });

export async function loadProgrammes(token: string): Promise<Loaded<Programme[]>> {
  const r = await outcome(apiClient(token).GET("/v1/admin/programmes", {}));
  return r.ok ? { ok: true, data: r.data.items } : fail(r.status);
}
export async function loadProgramme(token: string, id: number): Promise<Loaded<Programme>> {
  const r = await outcome(apiClient(token).GET("/v1/admin/programmes/{id}", { params: { path: { id } } }));
  return r.ok ? { ok: true, data: r.data } : fail(r.status);
}
export const PAGE_SIZE = 25;
/** One page of versions (newest first); `next` is the cursor of the following page. */
export async function loadVersions(token: string, id: number, cursor?: string): Promise<Loaded<Page<ProgrammeVersion>>> {
  const r = await outcome(apiClient(token).GET("/v1/admin/programmes/{id}/versions", { params: { path: { id }, query: { limit: PAGE_SIZE, cursor } } }));
  return r.ok ? { ok: true, data: { items: r.data.items, next: r.data.next_cursor } } : fail(r.status);
}
/** One page of enrolments; `next` is the cursor of the following page. */
export async function loadEnrolments(token: string, id: number, cursor?: string): Promise<Loaded<Page<Enrolment>>> {
  const r = await outcome(apiClient(token).GET("/v1/admin/programmes/{id}/enrolments", { params: { path: { id }, query: { limit: PAGE_SIZE, cursor } } }));
  return r.ok ? { ok: true, data: { items: r.data.items, next: r.data.next_cursor } } : fail(r.status);
}
export async function loadGifts(token: string, programmeId?: number): Promise<Loaded<Gift[]>> {
  const r = await outcome(apiClient(token).GET("/v1/admin/gifts", { params: { query: { programme_id: programmeId } } }));
  return r.ok ? { ok: true, data: r.data.items } : fail(r.status);
}
