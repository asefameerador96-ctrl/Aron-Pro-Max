// Marker pictures for the national dashboard maps, as inline SVG data URLs (no image request, no extra library). Colours follow the brief:
// route pins Red / Amber / Green by geo-fencing %, outlet pins black (not called), green (inside the fence), red (outside, photo).
import type { PinColor } from "./query";

export const PIN_FILL: Record<PinColor | "black" | "blue" | "yellow" | "teal", string> = { red: "#d5484a", amber: "#e89a0c", green: "#1f9d55", black: "#1a1a1a", blue: "#1565c0", yellow: "#f2c200", teal: "#0a9b8e" };
const url = (svg: string) => `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`;

/** A map pin (teardrop) with an optional letter inside, 28 x 38. */
export function pinIcon(fill: string, letter = ""): string {
  return url(`<svg xmlns="http://www.w3.org/2000/svg" width="28" height="38" viewBox="0 0 28 38"><path d="M14 1C6.8 1 1 6.8 1 14c0 9.7 13 23 13 23s13-13.3 13-23C27 6.8 21.2 1 14 1z" fill="${fill}" stroke="#ffffff" stroke-width="2"/><circle cx="14" cy="14" r="5" fill="#ffffff"/>${letter ? `<text x="14" y="14" text-anchor="middle" dominant-baseline="central" font-family="Roboto,Arial,sans-serif" font-size="8" font-weight="700" fill="${fill}">${letter}</text>` : ""}</svg>`);
}

/** The count bubble of a cluster: red like the original portal, magenta for a very large one. */
export function clusterIcon(count: number): string {
  const big = count >= 1000;
  const r = count >= 100 ? 20 : count >= 10 ? 17 : 14;
  const fill = big ? "#e021b4" : "#d5484a";
  return url(`<svg xmlns="http://www.w3.org/2000/svg" width="${r * 2 + 12}" height="${r * 2 + 12}"><circle cx="${r + 6}" cy="${r + 6}" r="${r + 5}" fill="${fill}" fill-opacity="0.28"/><circle cx="${r + 6}" cy="${r + 6}" r="${r}" fill="${fill}" stroke="#ffffff" stroke-width="2"/><text x="50%" y="50%" text-anchor="middle" dominant-baseline="central" font-family="Roboto,Arial,sans-serif" font-size="12" font-weight="700" fill="#ffffff">${count}</text></svg>`);
}

/** The chequered flag of "Sale Start Position". */
export function flagIcon(): string {
  return url(`<svg xmlns="http://www.w3.org/2000/svg" width="30" height="34" viewBox="0 0 30 34"><path d="M5 2v30" stroke="#1a1a1a" stroke-width="3"/><path d="M5 4h20v14H5z" fill="#ffffff" stroke="#1a1a1a" stroke-width="2"/><path d="M5 4h5v5H5zM15 4h5v5h-5zM10 9h5v5h-5zM20 9h5v5h-5zM5 14h5v4H5zM15 14h5v4h-5z" fill="#1a1a1a"/></svg>`);
}

/** The person marker of "SR Last Position". */
export function personIcon(): string {
  return url(`<svg xmlns="http://www.w3.org/2000/svg" width="26" height="38" viewBox="0 0 26 38"><circle cx="13" cy="7" r="5.5" fill="#1a1a1a" stroke="#ffffff" stroke-width="2"/><path d="M6 36V20c0-4 3-7 7-7s7 3 7 7v16h-4V24h-1v12h-4V24h-1v12z" fill="#1a1a1a" stroke="#ffffff" stroke-width="1.5"/></svg>`);
}
