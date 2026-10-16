// Theme, density, glass tier and sidebar state (docs/design/web-glass.md s2.6, s3, s8.2). The script below runs inline before
// first paint (with the CSP nonce) so the page never flashes the wrong theme; ThemeMenu re-applies the same rules on change.
// Every storage access is wrapped: a blocked localStorage must never break the page.
export type Theme = "system" | "light" | "dark" | "sunlight";
export type Density = "comfortable" | "regular" | "compact";
export type Glass = "full" | "lite" | "off";

export const THEME_KEY = "aron.theme";
export const DENSITY_KEY = "aron.density";
export const GLASS_KEY = "aron.glass";
export const NAV_KEY = "aron.nav";

export const THEMES: readonly Theme[] = ["system", "light", "dark", "sunlight"];
export const DENSITIES: readonly Density[] = ["comfortable", "regular", "compact"];

/** Resolve the stamped theme: the user's choice, or for `system` the OS preference; the default is light (docs/32 s2a item 2). */
export function resolveTheme(choice: Theme, prefers: (q: string) => boolean): Exclude<Theme, "system"> {
  if (choice !== "system") return choice;
  if (prefers("(prefers-contrast: more)")) return "sunlight";
  return prefers("(prefers-color-scheme: dark)") ? "dark" : "light";
}

/** Tier A only when real blur exists and nobody asked for less transparency; sunlight and more-contrast are always solid (C). */
export function resolveGlass(theme: Exclude<Theme, "system">, prefers: (q: string) => boolean, supportsBlur: boolean, userChoice: Glass | null): Glass {
  if (theme === "sunlight" || prefers("(prefers-contrast: more)") || prefers("(forced-colors: active)") || userChoice === "off") return "off";
  if (!supportsBlur || prefers("(prefers-reduced-transparency: reduce)") || userChoice === "lite") return "lite";
  return "full";
}

/* The inline form of the two functions above. Keep both in step (tests/theme-script.test.ts runs this against a fake DOM). */
export const THEME_SCRIPT = `(()=>{try{var d=document.documentElement,g=function(k){try{return localStorage.getItem(k)}catch(e){return null}},q=function(m){try{return matchMedia(m).matches}catch(e){return false}};
var t=g("${THEME_KEY}")||"light",th=t==="system"?(q("(prefers-contrast: more)")?"sunlight":q("(prefers-color-scheme: dark)")?"dark":"light"):t;
if(th!=="light"&&th!=="dark"&&th!=="sunlight")th="light";
var b=!!(window.CSS&&CSS.supports&&(CSS.supports("backdrop-filter","blur(1px)")||CSS.supports("-webkit-backdrop-filter","blur(1px)")));
var u=g("${GLASS_KEY}"),gl=(th==="sunlight"||q("(prefers-contrast: more)")||q("(forced-colors: active)")||u==="off")?"off":(!b||q("(prefers-reduced-transparency: reduce)")||u==="lite")?"lite":"full";
var de=g("${DENSITY_KEY}");if(de!=="comfortable"&&de!=="compact")de="regular";
d.setAttribute("data-theme",th);d.setAttribute("data-glass",gl);d.setAttribute("data-density",de);if(g("${NAV_KEY}")==="rail")d.setAttribute("data-nav","rail");}catch(e){}})();`;
