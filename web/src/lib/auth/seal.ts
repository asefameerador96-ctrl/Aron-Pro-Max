// Authenticated encryption of cookie payloads (AES-256-GCM). The browser holds only ciphertext it cannot read or forge:
// it never sees an access token, mfa token or user record (docs/24 s6.5). Each cookie kind has its own purpose string
// bound as AAD so one sealed value cannot be replayed as another.
import { createCipheriv, createDecipheriv, hkdfSync, randomBytes } from "node:crypto";

const DEV_SECRET = "dev-only-session-secret-change-me-0123456789";

export function sessionSecret(): string {
  const s = process.env.ARON_SESSION_SECRET;
  if (s && s.length >= 32) return s;
  if (process.env.NODE_ENV === "production") {
    throw new Error("ARON_SESSION_SECRET (32+ characters) is required in production");
  }
  return DEV_SECRET;
}

function key(secret: string, purpose: string): Buffer {
  return Buffer.from(hkdfSync("sha256", secret, "aron-web-bff", `cookie:${purpose}`, 32));
}

export function seal(payload: unknown, purpose: string, ttlSeconds: number, secret = sessionSecret()): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(secret, purpose), iv);
  cipher.setAAD(Buffer.from(purpose));
  const body = JSON.stringify({ p: payload, exp: Math.floor(Date.now() / 1000) + ttlSeconds });
  const ct = Buffer.concat([cipher.update(body, "utf8"), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), ct]).toString("base64url");
}

export function open<T>(value: string | undefined, purpose: string, secret = sessionSecret()): T | null {
  if (!value) return null;
  try {
    const raw = Buffer.from(value, "base64url");
    if (raw.length < 12 + 16 + 2) return null;
    const decipher = createDecipheriv("aes-256-gcm", key(secret, purpose), raw.subarray(0, 12));
    decipher.setAAD(Buffer.from(purpose));
    decipher.setAuthTag(raw.subarray(12, 28));
    const body = Buffer.concat([decipher.update(raw.subarray(28)), decipher.final()]).toString("utf8");
    const parsed = JSON.parse(body) as { p: T; exp: number };
    if (typeof parsed.exp !== "number" || parsed.exp < Math.floor(Date.now() / 1000)) return null;
    return parsed.p;
  } catch {
    return null;
  }
}
