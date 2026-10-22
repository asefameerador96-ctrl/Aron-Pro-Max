// The Aron logo (owner-supplied AKTCL artwork, D-BRAND-01) in the places the portal draws a brand: the sidebar rail and its drawer,
// the mobile top bar and the sign-in card. It replaces the drawn round monogram and the mobile bar's dashboard glyph.
//
// The shell keeps its existing look (PARITY-SPEC s4): a round white badge. The logo is a tall slanted letter with a navy outline,
// so it sits INSIDE that round plate, scaled to 88% so its narrow top and bottom tips clear the circle (checked by eye at 28 to
// 72 px). The plate is what keeps it legible: on the crimson-purple rail the navy outline would vanish into the gradient, and on
// the dark theme's bar and card it fades to about 1.1:1. The plate uses the rail's own white token, so it is white in every theme
// like the sidebar it came from; on the white top bar and the light sign-in card it simply disappears and the logo stands alone.
//
// A plain <img>, deliberately not next/image: the files are 4 to 46 KB, next/image would route them through /_next/image, the
// Cache-Control rule in next.config.ts already makes /brand/* cacheable and nothing here needs the optimiser. Width and height
// are explicit (no layout shift), the mark is decorative (alt is empty: it always sits beside the visible word "Aron"), and a
// failed load leaves an empty plate, never error text, so no page ever waits on its logo.
const FILES = [64, 128, 256] as const;
const url = (px: number): string => `/brand/aron-mark-${px}.png`;
/** The smallest shipped file that still covers `px` device pixels. */
const fileFor = (px: number): number => FILES.find((f) => f >= px) ?? FILES[FILES.length - 1]!;
/** Share of the plate's diameter the logo box takes. */
const INNER = 0.88;

export function BrandMark({ size = 36, priority = false, className = "" }: { size?: number; priority?: boolean; className?: string }) {
  const inner = Math.round(size * INNER);
  // 1x, 2x and 3x candidates; a file that covers several densities is listed once per density (valid, and exact).
  const srcSet = [1, 2, 3].map((d) => `${url(fileFor(inner * d))} ${d}x`).join(", ");
  return (
    <span className={`inline-flex shrink-0 items-center justify-center rounded-full bg-on-rail ${className}`.trim()} style={{ width: size, height: size }} aria-hidden="true">
      {/* eslint-disable-next-line @next/next/no-img-element -- see the note at the top of this file */}
      <img src={url(fileFor(inner))} srcSet={srcSet} width={inner} height={inner} alt="" decoding="async" fetchPriority={priority ? "high" : undefined} draggable={false} className="object-contain" />
    </span>
  );
}
