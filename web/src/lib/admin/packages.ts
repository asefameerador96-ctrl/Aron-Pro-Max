// App block list helpers (N-046): package-name validation, default groups and the default lists of docs/24 s10.6.
export const PACKAGE_PATTERN = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/; // contract PackageName
export const PACKAGE_EXAMPLE = "com.example.app";
export const PACKAGE_MAX_LEN = 120;

export type PackageGroup = "social" | "video" | "games" | "messaging" | "essential" | "other";

/** Default categories (sponsor decision, docs/23 Q1): shown beside each package; the stored value is the package name only. */
export const KNOWN_GROUPS: Record<string, PackageGroup> = {
  "com.facebook.katana": "social",
  "com.facebook.lite": "social",
  "com.instagram.android": "social",
  "com.snapchat.android": "social",
  "com.zhiliaoapp.musically": "video",
  "com.ss.android.ugc.trill": "video",
  "com.google.android.youtube": "video",
  "com.dts.freefireth": "games",
  "com.tencent.ig": "games",
  "com.whatsapp": "messaging",
  "com.facebook.orca": "messaging",
};

export const DEFAULT_BLOCKED: readonly string[] = ["com.facebook.katana", "com.facebook.lite", "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.google.android.youtube", "com.snapchat.android", "com.dts.freefireth", "com.tencent.ig"];

export const groupOf = (pkg: string): PackageGroup => KNOWN_GROUPS[pkg] ?? "other";

export function validatePackage(name: string, existing: readonly string[], max: number): "invalid" | "duplicate" | "too_many" | null {
  const n = name.trim();
  if (n.length > PACKAGE_MAX_LEN || !PACKAGE_PATTERN.test(n)) return "invalid";
  if (existing.includes(n)) return "duplicate";
  if (existing.length >= max) return "too_many";
  return null;
}
