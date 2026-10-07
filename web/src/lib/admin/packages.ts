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

/** Never blockable: the dialer, messaging, maps, camera, settings, keyboard and launcher (docs/24 s10.6) and the Aron apps. */
export const ESSENTIAL_PACKAGES: readonly string[] = [
  "com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
  "com.google.android.apps.maps", "com.sec.android.app.camera", "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.android.settings",
  "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard", "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher", "com.android.launcher3",
];
export const ARON_PREFIX = "com.aktcl.aron";
export const isProtected = (pkg: string, extra: readonly string[] = []): boolean => ESSENTIAL_PACKAGES.includes(pkg) || extra.includes(pkg) || pkg === ARON_PREFIX || pkg.startsWith(`${ARON_PREFIX}.`);

export const groupOf = (pkg: string): PackageGroup => KNOWN_GROUPS[pkg] ?? (isProtected(pkg) ? "essential" : "other");

export function validatePackage(name: string, existing: readonly string[], max: number, protectedList?: readonly string[]): "invalid" | "duplicate" | "too_many" | "protected" | null {
  const n = name.trim();
  if (n.length > PACKAGE_MAX_LEN || !PACKAGE_PATTERN.test(n)) return "invalid";
  if (protectedList && isProtected(n, protectedList)) return "protected";
  if (existing.includes(n)) return "duplicate";
  if (existing.length >= max) return "too_many";
  return null;
}
