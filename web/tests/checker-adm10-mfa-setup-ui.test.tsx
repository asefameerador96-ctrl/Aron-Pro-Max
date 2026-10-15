// CHECKER ADM-10 (T1, security) for F-WEB-043b: the MfaSetup component and the login form's TOTP step, driven without a DOM
// (state cells are mocked, as in checker-adm6-ui). The enrol responses are the real BFF route's answers against the mock.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { MfaSetup } from "@/components/mfa-setup";
import { LoginForm } from "@/components/login-form";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as enrolPost } from "@/app/api/bff/mfa/enrol/route";
import { MFA_COOKIE } from "@/lib/auth/cookies";
import { problemMessage, t as translate, type Locale, type MessageKey } from "@/lib/i18n";
import { jar, req, setupMock } from "./helpers/bff";

const hooks = vi.hoisted(() => ({ cells: [] as unknown[], i: 0, locale: "en" as string }));
vi.mock("react", async (orig: () => Promise<Record<string, unknown>>) => {
  const real = await orig();
  return {
    ...real,
    useState: (init: unknown) => {
      const k = hooks.i++;
      if (!(k in hooks.cells)) hooks.cells[k] = typeof init === "function" ? (init as () => unknown)() : init;
      return [hooks.cells[k], (v: unknown) => void (hooks.cells[k] = typeof v === "function" ? (v as (p: unknown) => unknown)(hooks.cells[k]) : v)];
    },
  };
});
vi.mock("@/components/i18n-provider", async () => {
  const i18n = await import("@/lib/i18n");
  return { useI18n: () => ({ locale: hooks.locale, t: (k: MessageKey) => i18n.t(hooks.locale as Locale, k), number: (n: number) => String(n), problem: (c: string | undefined) => i18n.problemMessage(hooks.locale as Locale, c) }) };
});

setupMock();

type El = { type?: unknown; props: Record<string, unknown> };
function walk(n: unknown, f: (e: El) => void) {
  if (Array.isArray(n)) return n.forEach((c) => walk(c, f));
  if (!n || typeof n !== "object") return;
  const e = n as { props?: Record<string, unknown>; type?: unknown };
  if (e.props) {
    f({ type: e.type, props: e.props });
    walk(e.props.children, f);
  }
}
const find = (tree: unknown, pred: (e: El) => boolean): El[] => {
  const out: El[] = [];
  walk(tree, (e) => pred(e) && out.push(e));
  return out;
};
const byTestId = (tree: unknown, id: string) => find(tree, (e) => e.props["data-testid"] === id)[0];
const text = (n: unknown): string => (typeof n === "string" || typeof n === "number" ? String(n) : Array.isArray(n) ? n.map(text).join("") : n && typeof n === "object" && "props" in n ? text((n as El).props.children) : "");

function renderSetup(): ReactElement {
  hooks.i = 0;
  return MfaSetup() as ReactElement;
}
function renderLogin(): ReactElement {
  hooks.i = 0;
  return LoginForm({ next: "/" }) as ReactElement;
}

/** fetch in the component goes to the real enrol route with the given cookie jar. */
const realFetch = globalThis.fetch;
function routeFetch(cookies: Record<string, string>) {
  const bff = vi.fn(async (path: string, init?: RequestInit) => {
    expect(path).toBe("/api/bff/mfa/enrol");
    expect(init?.method).toBe("POST");
    return enrolPost(req(path, "POST", undefined, cookies));
  });
  // the route itself calls the mock API through fetch: pass those through
  const f = (input: RequestInfo | URL, init?: RequestInit) => (typeof input === "string" && input.startsWith("/api/bff/") ? bff(input, init) : realFetch(input, init));
  return Object.assign(f, { bff });
}

beforeEach(() => {
  hooks.cells = [];
  hooks.locale = "en";
});
afterEach(() => vi.unstubAllGlobals());

describe("ADM-10 MfaSetup: held", () => {
  it("success shows the QR (the URI only goes to the in-page SVG), the base32 key and ten codes; nothing is written to storage", async () => {
    const c = jar(await loginPost(req("/api/bff/login", "POST", { username: "newadmin1", password: "newadmin-pass-1" })));
    const f = routeFetch({ [MFA_COOKIE]: c[MFA_COOKIE]! });
    vi.stubGlobal("fetch", f);
    const ls = vi.fn();
    vi.stubGlobal("localStorage", { setItem: ls });
    vi.stubGlobal("sessionStorage", { setItem: ls });
    await (byTestId(renderSetup(), "mfa-setup")!.props.onClick as () => Promise<void>)();
    const tree = renderSetup();
    expect(text(byTestId(tree, "mfa-secret"))).toBe("JBSWY3DPEHPK3PXP");
    expect(find(byTestId(tree, "mfa-recovery-codes"), (e) => e.type === "li")).toHaveLength(10);
    const qr = find(tree, (e) => typeof e.props.text === "string")[0]!;
    expect(qr.props.text).toMatch(/^otpauth:\/\/totp\/Aron:newadmin1\?secret=JBSWY3DPEHPK3PXP&issuer=Aron$/);
    expect(f.bff).toHaveBeenCalledTimes(1);
    expect(ls).not.toHaveBeenCalled();
  });

  it("a confirmed administrator sees the 'already set up' message, never a QR", async () => {
    const c = jar(await loginPost(req("/api/bff/login", "POST", { username: "madmin1", password: "admin-pass-1" })));
    vi.stubGlobal("fetch", routeFetch({ [MFA_COOKIE]: c[MFA_COOKIE]! }));
    await (byTestId(renderSetup(), "mfa-setup")!.props.onClick as () => Promise<void>)();
    const tree = renderSetup();
    expect(byTestId(tree, "mfa-setup-result")).toBeUndefined();
    expect(text(byTestId(tree, "mfa-setup-error"))).toBe(translate("en", "auth.mfa.setup.already"));
  });

  it("the code input: numeric keypad, one-time-code, [0-9]{6}; recovery mode takes text up to 9; the setup button is type=button (no form submit)", () => {
    hooks.cells = ["mfa", false, null, false, false];
    let tree = renderLogin();
    const code = () => find(tree, (e) => e.props.name === "code")[0]!.props;
    expect(code()).toMatchObject({ inputMode: "numeric", autoComplete: "one-time-code", pattern: "[0-9]{6}", maxLength: 6, required: true });
    (byTestId(tree, "mfa-toggle-recovery")!.props.onClick as () => void)();
    tree = renderLogin();
    expect(code()).toMatchObject({ inputMode: "text", maxLength: 9 });
    expect(find(tree, (e) => e.type === MfaSetup)).toHaveLength(1);
    expect(byTestId(tree, "mfa-toggle-recovery")!.props.type).toBe("button");
  });
});

describe("ADM-10 MfaSetup: defects", () => {
  it("DEFECT: after the 5-minute mfa cookie ran out, 'Set up' says 'That code is not right' (no code was typed); it must say to sign in again", async () => {
    // The BFF answers a missing/expired mfa cookie with 401 ERR_AUTH_MFA_INVALID; MfaSetup maps it through problem().
    vi.stubGlobal("fetch", routeFetch({}));
    await (byTestId(renderSetup(), "mfa-setup")!.props.onClick as () => Promise<void>)();
    const msg = text(byTestId(renderSetup(), "mfa-setup-error"));
    expect(msg).not.toBe(problemMessage("en", "ERR_AUTH_MFA_INVALID"));
    expect(msg).toMatch(/sign in again/i);
  });

  it("DEFECT (a11y, low): the secret and recovery codes appear with no announcement (no live region, no focus target) after the button unmounts", async () => {
    const c = jar(await loginPost(req("/api/bff/login", "POST", { username: "newadmin1", password: "newadmin-pass-1" })));
    vi.stubGlobal("fetch", routeFetch({ [MFA_COOKIE]: c[MFA_COOKIE]! }));
    await (byTestId(renderSetup(), "mfa-setup")!.props.onClick as () => Promise<void>)();
    const section = byTestId(renderSetup(), "mfa-setup-result")!;
    const announced = section.props["aria-live"] !== undefined || section.props.role === "status" || section.props.tabIndex !== undefined || section.props.ref !== undefined || find(section, (e) => e.props.autoFocus === true).length > 0;
    expect(announced).toBe(true);
  });
});
