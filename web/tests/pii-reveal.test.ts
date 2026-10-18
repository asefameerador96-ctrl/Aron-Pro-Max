// PII reveal helpers: the countdown text and the message for each refusal of POST /v1/auth/reauth.
import { describe, expect, it } from "vitest";
import { leftText, revealError } from "@/components/dash/pii-reveal";

describe("pii reveal", () => {
  it("counts down in m:ss and never below zero", () => {
    expect(leftText(15 * 60_000, 0)).toBe("15:00");
    expect(leftText(61_500, 0)).toBe("1:02");
    expect(leftText(0, 5_000)).toBe("0:00");
  });
  it("maps every refusal to one message", () => {
    expect(revealError(401, "ERR_AUTH_INVALID_CREDENTIALS")).toBe("bad"); // wrong password and wrong code share it
    expect(revealError(400, "ERR_VALIDATION")).toBe("bad");
    expect(revealError(403, "ERR_AUTH_ACCOUNT_LOCKED")).toBe("locked");
    expect(revealError(401, "ERR_AUTH_MFA_INVALID")).toBe("resignin");
    expect(revealError(429, "ERR_RATE_LIMITED")).toBe("limited");
    expect(revealError(503, "ERR_SERVICE_UNAVAILABLE")).toBe("failed");
  });
});
