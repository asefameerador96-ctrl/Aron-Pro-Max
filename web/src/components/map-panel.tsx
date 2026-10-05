import { t, type Locale } from "@/lib/i18n";

/** Google Maps needs a referrer-restricted key from the GitHub secret MAPS_WEB_KEY (NEXT_PUBLIC_MAPS_WEB_KEY at build).
 *  Without it the panel is a placeholder, so builds and tests never need the secret (docs/24 s13.6). */
export function mapsEnabled(): boolean {
  return Boolean(process.env.NEXT_PUBLIC_MAPS_WEB_KEY);
}

export function MapPanel({ locale }: { locale: Locale }) {
  if (!mapsEnabled()) {
    return (
      <div data-testid="map-placeholder" className="flex h-48 items-center justify-center rounded border border-dashed border-slate-300 bg-slate-100 text-sm text-slate-600">
        {t(locale, "dashboard.maps.placeholder")}
      </div>
    );
  }
  // The maps row mounts the Google Maps loader into this element.
  return <div data-testid="map-root" data-maps="enabled" className="h-48 rounded bg-slate-100" />;
}
