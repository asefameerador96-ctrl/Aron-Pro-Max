import { expect, type Page } from "@playwright/test";

export const MOCK = `http://127.0.0.1:${process.env.E2E_MOCK_PORT ?? 4010}`;

export async function resetMock(): Promise<void> {
  await fetch(`${MOCK}/__mock/reset`, { method: "POST" });
}

/** Password step. Use `totp` for admin roles (the mock accepts 123456). */
export async function login(page: Page, username: string, password: string, totp?: string): Promise<void> {
  await page.goto("/login");
  await page.getByTestId("login-form").locator('input[name="username"]').fill(username);
  await page.getByTestId("login-form").locator('input[name="password"]').fill(password);
  await page.getByTestId("login-form").locator('button[type="submit"]').click();
  if (totp) {
    await expect(page.getByTestId("mfa-form")).toBeVisible();
    await page.getByTestId("mfa-form").locator('input[name="code"]').fill(totp);
    await page.getByTestId("mfa-form").locator('button[type="submit"]').click();
  }
}

/** Login that must succeed: also waits for the post-login navigation so later `goto` calls do not race it. */
export async function loginOk(page: Page, username: string, password: string, totp?: string): Promise<void> {
  await login(page, username, password, totp);
  await expect(page.getByTestId("who")).toBeVisible();
}

/** A Dhaka business date `n` days from now (the portal refuses past dates, so tests use relative ones). */
export function dhakaPlus(n: number): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Dhaka", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(Date.now() + n * 86_400_000));
}
