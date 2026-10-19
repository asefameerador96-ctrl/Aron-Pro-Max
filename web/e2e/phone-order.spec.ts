// Phone (360 px) card order: before (today's order) and after (the proposal: Needs attention and Net sales right after the four sales
// cards) side by side on one page. The product order is NOT changed here: the owner decides; the proposal is a CSS `order` override that
// exists only inside this test. The comparison is attached to the report and written to docs/evidence when WRITE_EVIDENCE is set.
import { writeFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { loginOk, resetMock } from "./helpers";

test("phone card order: before and after comparison", async ({ page }, info) => {
  test.setTimeout(90_000);
  await resetMock();
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  await loginOk(page, "tso334", "tso-pass-1"); // the helper expects the desktop shell
  await page.getByRole("link", { name: "English" }).click();
  await page.setViewportSize({ width: 360, height: 740 });
  await page.goto("/");
  await expect(page.getByTestId("dashboard-tiles")).toBeVisible();
  await page.waitForLoadState("networkidle");
  const before = await page.screenshot({ fullPage: true });
  await page.addStyleTag({ content: `
    [data-testid=dashboard-tiles] > * { order: 5; }
    [data-testid=dashboard-tiles] > [data-testid^="tile-cat-"] { order: 0; }
    [data-testid=dashboard-tiles] > [data-testid=tile-attention] { order: 1; }
    [data-testid=dashboard-tiles] > [data-testid=tile-sales] { order: 2; }` });
  const after = await page.screenshot({ fullPage: true });
  const b64 = (b: Buffer) => `data:image/png;base64,${b.toString("base64")}`;
  await page.setViewportSize({ width: 800, height: 900 });
  await page.setContent(`<body style="margin:0;font:14px system-ui;background:#fff"><div style="display:flex;gap:24px;padding:16px;align-items:flex-start">
    <figure style="margin:0"><figcaption style="font-weight:600;padding-bottom:8px">Before (today)</figcaption><img src="${b64(before)}" width="360"></figure>
    <figure style="margin:0"><figcaption style="font-weight:600;padding-bottom:8px">After (proposal): Needs attention and Net sales after the sales cards</figcaption><img src="${b64(after)}" width="360"></figure></div></body>`);
  const out = await page.screenshot({ fullPage: true });
  await info.attach("phone-order-before-after", { body: out, contentType: "image/png" });
  if (process.env.WRITE_EVIDENCE) writeFileSync(process.env.WRITE_EVIDENCE, out);
});
