// CHECKER N-010 D3: a transient API outage during token refresh must not destroy the user's session.
import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { authenticate } from "@/lib/api/guard";
import { RT_COOKIE, SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { seal } from "@/lib/auth/seal";
import type { SessionData } from "@/lib/auth/session";

describe("refresh during an API outage", () => {
  it("answers a retryable 503 and keeps the cookies (does not clear the session)", async () => {
    process.env.ARON_API_BASE_URL = "http://127.0.0.1:1"; // nothing listens: connection refused
    const s: SessionData = {
      at: "at.x",
      atExp: Date.now() + 10_000, // inside the refresh skew
      user: { user_id: 2001, username: "tso334", full_name: "T", role: "TSO", designation: "TSO", locale: "bn" },
      scope: { scope_version: 7, nodes: [] },
    };
    const req = new NextRequest("http://localhost:3000/api/bff/session/x", {
      method: "POST",
      headers: { host: "localhost:3000", origin: "http://localhost:3000", cookie: `${SESSION_COOKIE}=${seal(s, SESSION_PURPOSE, 3600)}; ${RT_COOKIE}=${"a".repeat(43)}` },
    });
    const r = await authenticate(req, "/api/bff/session/x");
    expect("finish" in r).toBe(false);
    const res = r as Response;
    expect(res.status).toBe(503);
    const cleared = res.headers.getSetCookie().filter((c) => /^aron_(sess|rt)=;|Expires=Thu, 01 Jan 1970/.test(c));
    expect(cleared, "session cookies must survive a network failure").toEqual([]);
  });
});
