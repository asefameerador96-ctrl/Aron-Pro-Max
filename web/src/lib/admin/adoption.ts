import type { Device } from "./types";

export interface Adoption {
  flavour: string;
  version: string;
  devices: number;
}

/** Phones per app flavour and version (revoked and replaced phones are not counted: they no longer sell). */
export function adoptionByVersion(devices: readonly Device[]): Adoption[] {
  const m = new Map<string, Adoption>();
  for (const d of devices) {
    if (d.status === "revoked" || d.status === "replaced") continue;
    const version = d.app_version ?? "?";
    const k = `${d.flavour}|${version}`;
    const e = m.get(k) ?? { flavour: d.flavour, version, devices: 0 };
    e.devices++;
    m.set(k, e);
  }
  return [...m.values()].sort((a, b) => a.flavour.localeCompare(b.flavour) || b.version.localeCompare(a.version, undefined, { numeric: true }));
}
