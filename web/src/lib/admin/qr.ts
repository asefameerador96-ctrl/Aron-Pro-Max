import qrcode from "qrcode-generator";

/** QR matrix of the text, byte-exact UTF-8 (Bangla, accents), error correction M and L for the longest payloads; null when it cannot fit. */
export function makeQr(text: string): { size: number; isDark: (r: number, c: number) => boolean } | null {
  qrcode.stringToBytes = qrcode.stringToBytesFuncs["UTF-8"] ?? qrcode.stringToBytes;
  for (const level of ["M", "L"] as const) {
    try {
      const qr = qrcode(0, level);
      qr.addData(text, "Byte");
      qr.make();
      return { size: qr.getModuleCount(), isDark: (r, c) => qr.isDark(r, c) };
    } catch {
      /* try the next level */
    }
  }
  return null;
}
