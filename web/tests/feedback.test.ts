import { describe, expect, it } from "vitest";
import { setupMock } from "./helpers/bff";

const { mock, signIn, act } = setupMock();
const F1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const REASON = "Reason that is long enough";

describe("feedback inbox (F-ADM-028)", () => {
  it("sets the status with a reason and adds an audit row; a short reason is refused", async () => {
    const admin = await signIn("madmin1");
    expect((await act("feedback", F1, "status", { values: { status: "resolved" }, reason: "short" }, admin)).status).toBe(400);
    expect((await act("feedback", F1, "status", { values: { status: "resolved" }, reason: REASON }, admin)).status).toBe(200);
    expect(mock.state.tables.feedback!.find((r) => r.feedback_uuid === F1)).toMatchObject({ status: "resolved" });
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "feedback", reason: REASON });
  });
  it("refuses an unknown status and a read-only role", async () => {
    const admin = await signIn("madmin1");
    expect((await act("feedback", F1, "status", { values: { status: "deleted" }, reason: REASON }, admin)).status).toBe(400);
    const support = await signIn("msupport1");
    expect((await act("feedback", F1, "status", { values: { status: "closed" }, reason: REASON }, support)).status).toBe(403);
  });
});
