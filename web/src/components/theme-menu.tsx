"use client";
// Theme (system, light, dark, high contrast) and density (web-glass s6, s8.2). The choice is saved per browser and stamped on
// <html> at once; the pre-paint script applies it on the next load. Built on <details> so it needs no positioning script.
import { useEffect, useRef, useSyncExternalStore } from "react";
import { DENSITIES, DENSITY_KEY, GLASS_KEY, NAV_KEY, THEMES, THEME_KEY, resolveGlass, resolveTheme, type Density, type Theme } from "@/lib/ui/theme";
import { useI18n } from "./i18n-provider";
import { Icon, type IconName } from "./kit/icons";

const THEME_ICON: Record<Theme, IconName> = { system: "monitor", light: "sun", dark: "moon", sunlight: "contrast" };

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* a blocked store only loses the preference */
  }
}
const prefers = (q: string): boolean => {
  try {
    return matchMedia(q).matches;
  } catch {
    return false;
  }
};

/** Re-apply the stamps from the current choice (same rules as THEME_SCRIPT). Also called on mount, in case hydration dropped them. */
export function applyTheme(choice: Theme, density: Density): void {
  const d = document.documentElement;
  const theme = resolveTheme(choice, prefers);
  const blur = typeof CSS !== "undefined" && !!CSS.supports && (CSS.supports("backdrop-filter", "blur(1px)") || CSS.supports("-webkit-backdrop-filter", "blur(1px)"));
  const user = read(GLASS_KEY);
  d.setAttribute("data-theme", theme);
  d.setAttribute("data-glass", resolveGlass(theme, prefers, blur, user === "lite" || user === "off" ? user : null));
  d.setAttribute("data-density", density);
  if (read(NAV_KEY) === "rail") d.setAttribute("data-nav", "rail");
}

const isTheme = (v: string | null): v is Theme => THEMES.includes(v as Theme);
const isDensity = (v: string | null): v is Density => DENSITIES.includes(v as Density);

// The choice lives in localStorage; React mirrors it through a store so the server snapshot (defaults) never mismatches.
const listeners = new Set<() => void>();
const subscribe = (cb: () => void) => {
  listeners.add(cb);
  window.addEventListener("storage", cb);
  return () => {
    listeners.delete(cb);
    window.removeEventListener("storage", cb);
  };
};
const notify = () => listeners.forEach((l) => l());
const getTheme = (): Theme => {
  const v = read(THEME_KEY);
  return isTheme(v) ? v : "light";
};
const getDensity = (): Density => {
  const v = read(DENSITY_KEY);
  return isDensity(v) ? v : "regular";
};

export function ThemeMenu() {
  const { t } = useI18n();
  const theme = useSyncExternalStore(subscribe, getTheme, () => "light" as Theme);
  const density = useSyncExternalStore(subscribe, getDensity, () => "regular" as Density);
  const details = useRef<HTMLDetailsElement>(null);

  useEffect(() => {
    // Re-apply the stamps after hydration (in case React dropped them) and close on outside click or Escape.
    applyTheme(getTheme(), getDensity());
    const onDown = (e: PointerEvent) => {
      if (details.current?.open && !details.current.contains(e.target as Node)) details.current.open = false;
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && details.current?.open) details.current.open = false;
    };
    document.addEventListener("pointerdown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, []);

  function chooseTheme(v: Theme) {
    write(THEME_KEY, v);
    applyTheme(v, density);
    notify();
  }
  function chooseDensity(v: Density) {
    write(DENSITY_KEY, v);
    applyTheme(theme, v);
    notify();
  }

  const option = "flex h-10 w-full items-center gap-2 rounded-chip px-3 text-body text-secondary hover:bg-pressed aria-[checked=true]:bg-accent-container aria-[checked=true]:text-accent-on-container aria-[checked=true]:font-semibold";
  return (
    <details ref={details} className="relative" data-testid="theme-menu">
      <summary className="flex h-10 w-10 cursor-pointer list-none items-center justify-center rounded-full text-secondary hover:bg-pressed [&::-webkit-details-marker]:hidden" title={t("theme.label")} aria-label={t("theme.label")}>
        <Icon name={THEME_ICON[theme]} />
      </summary>
      <div className="glass-pop absolute end-0 top-12 z-40 w-64 p-2" role="group" aria-label={t("theme.label")}>
        <p className="px-3 pb-1 pt-2 text-label text-secondary">{t("theme.label")}</p>
        <div role="radiogroup" aria-label={t("theme.label")}>
          {THEMES.map((v) => (
            <button key={v} type="button" role="radio" aria-checked={theme === v} onClick={() => chooseTheme(v)} className={option} data-theme-option={v}>
              <Icon name={THEME_ICON[v]} size={18} />
              <span className="flex-1 text-start">{t(`theme.${v}`)}</span>
              {theme === v ? <Icon name="check" size={16} /> : null}
            </button>
          ))}
        </div>
        <p className="px-3 pb-1 pt-3 text-label text-secondary">{t("density.label")}</p>
        <div role="radiogroup" aria-label={t("density.label")}>
          {DENSITIES.map((v) => (
            <button key={v} type="button" role="radio" aria-checked={density === v} onClick={() => chooseDensity(v)} className={option} data-density-option={v}>
              <Icon name="density" size={18} />
              <span className="flex-1 text-start">{t(`density.${v}`)}</span>
              {density === v ? <Icon name="check" size={16} /> : null}
            </button>
          ))}
        </div>
      </div>
    </details>
  );
}
