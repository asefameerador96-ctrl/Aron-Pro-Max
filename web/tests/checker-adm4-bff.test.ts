// Checker ADM-4 (F-ADM-028): BFF behaviour of the feedback status action.
import { describe, expect, it } from "vitest";
import { setupMock } from "./helpers/bff";

const { mock, signIn, act } = setupMock();
const F1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const REASON = "Reason that is long enough";
const status = (id = F1) => mock.state.tables.feedback!.find((r) => r.feedback_uuid === id)?.status;

describe("feedback status action (BFF)", () => {
  for (const u of ["tso334", "mtso1", "dmo1", "msupport1"] as const) {
    it(`CA4-20: ${u} gets 403 and nothing changes`, async () => {
      const c = await signIn(u);
      const before = mock.state.audit.length;
      expect((await act("feedback", F1, "status", { values: { status: "closed" }, reason: REASON }, c)).status).toBe(403);
      expect(mock.state.audit.length).toBe(before);
      expect(status()).toBe("new");
    });
  }
  it("CA4-21: no session is 401", async () => {
    expect((await act("feedback", F1, "status", { values: { status: "closed" }, reason: REASON }, {})).status).toBe(401);
  });
  it("CA4-22: reasons that are blank, 9 code points or over 500 are 400 and write no audit row", async () => {
    const c = await signIn("madmin1");
    const n = mock.state.audit.length;
    for (const reason of ["          ", "123456789", "অ".repeat(9), "x".repeat(501), undefined, 12345678901]) {
      expect((await act("feedback", F1, "status", { values: { status: "closed" }, reason }, c)).status, String(reason)).toBe(400);
    }
    expect(mock.state.audit.length).toBe(n);
    expect(status()).toBe("new");
  });
  it("CA4-23: a 10 code point Bangla reason (more than 10 UTF-16 units not required) is accepted and stored verbatim", async () => {
    const c = await signIn("madmin1");
    const reason = "অ".repeat(10);
    expect((await act("feedback", F1, "status", { values: { status: "in_progress" }, reason }, c)).status).toBe(200);
    expect(mock.state.audit.at(-1)).toMatchObject({ reason });
  });
  it("CA4-24: unknown extra members in values, a missing values object, and an unknown status are 400", async () => {
    const c = await signIn("madmin1");
    expect((await act("feedback", F1, "status", { values: { status: "closed", title: "hacked" }, reason: REASON }, c)).status).toBe(400);
    expect((await act("feedback", F1, "status", { reason: REASON }, c)).status).toBe(400);
    expect((await act("feedback", F1, "status", { values: { status: "NEW" }, reason: REASON }, c)).status).toBe(400);
    expect((await act("feedback", F1, "status", { values: { status: "" }, reason: REASON }, c)).status).toBe(400);
    expect(status()).toBe("new");
  });
  it("CA4-25: ids that are not a lower-case v4 uuid are 404 before any upstream call", async () => {
    const c = await signIn("madmin1");
    for (const id of ["1", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "aaaaaaaa-aaaa-1aaa-8aaa-aaaaaaaaaaaa", "aaaaaaaa-aaaa-4aaa-0aaa-aaaaaaaaaaaa", "..%2F..", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa%0A"]) {
      expect((await act("feedback", id, "status", { values: { status: "closed" }, reason: REASON }, c)).status, id).toBe(404);
    }
  });
  it("CA4-26: a well formed but unknown id is 404 and the audit log is unchanged", async () => {
    const c = await signIn("madmin1");
    const n = mock.state.audit.length;
    expect((await act("feedback", "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "status", { values: { status: "closed" }, reason: REASON }, c)).status).toBe(404);
    expect(mock.state.audit.length).toBe(n);
  });
  it("CA4-27: the reason is trimmed and sent as the audit reason", async () => {
    const c = await signIn("madmin1");
    expect((await act("feedback", F1, "status", { values: { status: "closed" }, reason: `  ${REASON}  ` }, c)).status).toBe(200);
    expect(mock.state.audit.at(-1)).toMatchObject({ reason: REASON });
  });
  it("CA4-28: every allowed status round-trips", async () => {
    const c = await signIn("madmin1");
    for (const s of ["in_progress", "resolved", "closed", "new"]) {
      expect((await act("feedback", F1, "status", { values: { status: s }, reason: REASON }, c)).status, s).toBe(200);
      expect(status()).toBe(s);
    }
  });
});
