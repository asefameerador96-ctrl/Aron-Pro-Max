import { describe, expect, it } from "vitest";
import { allowedFromMenus, loadAllowedMenus } from "@/lib/menu/matrix";
import { menuFor, type MenuItem } from "@/lib/menu/menu";
import { admin, setupMock, token, tso } from "./helpers/harness";

const h = setupMock();

describe("menus from GET /v1/me (cfg.web.menu_by_role)", () => {
  it("view grants only; no menus means no filter", () => {
    expect([...allowedFromMenus([{ menu_id: "a", actions: ["view", "edit"] }, { menu_id: "b", actions: ["export"] }])!]).toEqual(["a"]);
    expect(allowedFromMenus([])).toBeNull();
    expect(allowedFromMenus(undefined)).toBeNull();
  });
  it("a role reads its own menus from /v1/me (mock: the TSO row), a role without menus keeps the static list", async () => {
    expect([...(await loadAllowedMenus(token(await tso()), "TSO"))!]).toEqual(["dashboard"]);
    expect(await loadAllowedMenus(token(await admin()), "ADMIN")).toBeNull();
  });
  it("an unreachable API keeps the static menu", async () => {
    const t0 = token(await tso());
    const base = process.env.ARON_API_BASE_URL;
    process.env.ARON_API_BASE_URL = "http://127.0.0.1:9";
    try {
      expect(await loadAllowedMenus(t0, "TSO")).toBeNull();
    } finally {
      process.env.ARON_API_BASE_URL = base;
    }
  });
  it("menuFor hides an item the matrix does not grant and keeps items without a menu id", () => {
    const items: MenuItem[] = [
      { id: "a", menuId: "web_entry", labelKey: "menu.dashboard", href: "/a", roles: ["TSO"], group: "main" },
      { id: "b", menuId: "qc_entry", labelKey: "menu.dashboard", href: "/b", roles: ["TSO"], group: "main" },
      { id: "c", labelKey: "menu.dashboard", href: "/c", roles: ["TSO"], group: "main" },
    ];
    expect(menuFor("TSO", items, new Set(["web_entry"]))[0]!.items.map((i) => i.id)).toEqual(["a", "c"]);
    expect(menuFor("TSO", items, null)[0]!.items).toHaveLength(3);
  });
});
