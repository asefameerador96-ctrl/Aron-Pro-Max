// Signed browser IP for the API (docs/requests/backend-core-bff-client-ip.md, AUD-SEC-02 item 3): the API keys a web login's lockout by the
// browser's IP class instead of the BFF's. The IP is taken only from `X-Azure-ClientIP` (Front Door overwrites it; a browser cannot set it), and
// the HMAC covers the IP and the request id, so a header cannot be replayed on another request. No key file, no IP: no headers (the API then
// falls back to its per-username lockout). The key is a file mounted from Key Vault (ARON_BFF_IP_HMAC_KEY_FILE); it is never logged.
import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";

let cached: { path: string; key: Buffer | null } | null = null;

function key(env: Record<string, string | undefined>): Buffer | null {
  const path = env.ARON_BFF_IP_HMAC_KEY_FILE;
  if (!path) return null;
  if (cached?.path === path) return cached.key;
  let k: Buffer | null = null;
  try {
    const raw = readFileSync(path);
    k = raw.length > 0 ? raw : null;
  } catch {
    k = null;
  }
  cached = { path, key: k };
  return k;
}

/** Headers to add to a login or change-password call. `requestId` must be the `X-Request-Id` sent with that same call. */
export function clientIpHeaders(get: (name: string) => string | null | undefined, requestId: string, env: Record<string, string | undefined> = process.env): Record<string, string> {
  const k = key(env);
  const ip = get("x-azure-clientip")?.trim();
  if (!k || !ip || ip.length > 45 || !/^[0-9a-fA-F:.]+$/.test(ip)) return {};
  const sig = createHmac("sha256", k).update(`${ip}\n${requestId}`).digest("base64url");
  return { "X-Request-Id": requestId, "X-Aron-Client-Ip": ip, "X-Aron-Client-Ip-Sig": sig };
}

/** Forget the cached key (tests). */
export function resetClientIpKey(): void {
  cached = null;
}
