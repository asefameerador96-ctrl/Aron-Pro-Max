// A QR code drawn as an SVG of squares (no HTML injection, no network, works offline). The text never leaves the page.
import qrcode from "qrcode-generator";

export function QrCode({ text, label, size = 280 }: { text: string; label: string; size?: number }) {
  const qr = qrcode(0, "M");
  qr.addData(text, "Byte");
  qr.make();
  const n = qr.getModuleCount();
  const quiet = 4;
  const rects: React.ReactNode[] = [];
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) rects.push(<rect key={`${r}-${c}`} x={c + quiet} y={r + quiet} width="1.02" height="1.02" />);
  const dim = n + quiet * 2;
  return (
    <svg role="img" aria-label={label} viewBox={`0 0 ${dim} ${dim}`} width={size} height={size} className="rounded border border-slate-300 bg-white" shapeRendering="crispEdges" data-testid="qr-svg" data-modules={n}>
      <rect width={dim} height={dim} fill="#fff" />
      <g fill="#000">{rects}</g>
    </svg>
  );
}
