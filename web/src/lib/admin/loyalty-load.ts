// Server-side reads of the loyalty screens (portal-loyalty-web). Every read goes through the session token; the API derives the
// reach from it. `ProgrammeList` is one page of up to 500 (contract 1.12.2, no cursor): filters run here, at pilot size.
import { apiGet } from "@/components/admin/kit/page";
import type { Enrolment, Gift, Programme, ProgrammeVersion, Progress } from "./loyalty";

export type Loaded<T> = { ok: true; data: T } | { ok: false; offline: boolean };
const fail = (status: number): { ok: false; offline: boolean } => ({ ok: false, offline: status >= 500 || status === 0 });

export async function loadProgrammes(token: string): Promise<Loaded<Programme[]>> {
  const r = await apiGet<{ items: Programme[] }>("/v1/admin/programmes", token);
  return r.ok ? { ok: true, data: r.data.items } : fail(r.status);
}
/** There is no getProgramme in 1.12.2: the detail is the list entry (the same row, with its version for If-Match). */
export async function loadProgramme(token: string, id: number): Promise<Loaded<Programme | null>> {
  const all = await loadProgrammes(token);
  return all.ok ? { ok: true, data: all.data.find((p) => p.programme_id === id) ?? null } : all;
}
export async function loadVersions(token: string, id: number): Promise<Loaded<ProgrammeVersion[]>> {
  const r = await apiGet<{ items: ProgrammeVersion[] }>(`/v1/admin/loyalty/programmes/${id}/versions`, token, { limit: 50 });
  return r.ok ? { ok: true, data: r.data.items } : fail(r.status);
}
export async function loadProgress(token: string, id: number): Promise<Loaded<Progress>> {
  const r = await apiGet<Progress>(`/v1/admin/loyalty/programmes/${id}/progress`, token);
  return r.ok ? { ok: true, data: r.data } : fail(r.status);
}
export async function loadEnrolments(token: string, id: number): Promise<Loaded<Enrolment[]>> {
  const r = await apiGet<{ items: Enrolment[] }>(`/v1/admin/programmes/${id}/enrolments`, token, { limit: 50 });
  return r.ok ? { ok: true, data: r.data.items } : fail(r.status);
}
export async function loadGifts(token: string, programmeId?: number): Promise<Loaded<Gift[]>> {
  const r = await apiGet<{ items: Gift[] }>("/v1/admin/gifts", token, { programme_id: programmeId });
  return r.ok ? { ok: true, data: r.data.items } : fail(r.status);
}
