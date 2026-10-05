import { describe, expect, it, vi } from "vitest";
import { open, seal } from "@/lib/auth/seal";

const SECRET = "unit-test-secret-0123456789abcdef-xyz";

describe("sealed cookies", () => {
  it("round-trips and is not readable", () => {
    const v = seal({ at: "secret-token", n: 1 }, "session", 60, SECRET);
    expect(v).not.toContain("secret-token");
    expect(Buffer.from(v, "base64url").toString("utf8")).not.toContain("secret-token");
    expect(open(v, "session", SECRET)).toEqual({ at: "secret-token", n: 1 });
  });
  it("rejects a tampered value, another purpose, another secret, and garbage", () => {
    const v = seal({ a: 1 }, "session", 60, SECRET);
    const raw = Buffer.from(v, "base64url");
    raw[raw.length - 1] = (raw[raw.length - 1] ?? 0) ^ 1;
    expect(open(raw.toString("base64url"), "session", SECRET)).toBeNull();
    expect(open(v, "mfa", SECRET)).toBeNull();
    expect(open(v, "session", "another-secret-0123456789abcdef-xyz")).toBeNull();
    expect(open("not-a-cookie", "session", SECRET)).toBeNull();
    expect(open(undefined, "session", SECRET)).toBeNull();
    expect(open("", "session", SECRET)).toBeNull();
  });
  it("expires", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-05T00:00:00Z"));
    const v = seal({ a: 1 }, "session", 60, SECRET);
    vi.setSystemTime(new Date("2026-10-05T00:00:30Z"));
    expect(open(v, "session", SECRET)).toEqual({ a: 1 });
    vi.setSystemTime(new Date("2026-10-05T00:01:01Z"));
    expect(open(v, "session", SECRET)).toBeNull();
    vi.useRealTimers();
  });
});
