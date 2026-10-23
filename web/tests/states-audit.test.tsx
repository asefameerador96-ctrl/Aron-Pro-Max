// States and Bangla audit of the admin portal (D-WEB-ADMIN-STATES-01, CLAUDE.md rule 14): the shared states stay in the kit, every string stays in the i18n layer.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { CodeListEditor } from "@/components/admin/codelist-editor";
import { PackageListEditor } from "@/components/admin/config/package-list-editor";
import { SupervisorTargetsEditor } from "@/components/admin/config/supervisor-targets-editor";
import { PriceGrid } from "@/components/admin/price-grid";
import { SalesPlanEditor } from "@/components/admin/sales-plan-editor";
import { DataTable } from "@/components/admin/kit/data-table";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

const walk = (dir: string): string[] => readdirSync(dir).flatMap((n) => (statSync(join(dir, n)).isDirectory() ? walk(join(dir, n)) : n.endsWith(".tsx") ? [join(dir, n)] : []));

describe("empty states", () => {
  it("a data table with no rows says so in the page's own words", () => {
    const m = html(<DataTable columns={[{ key: "a", header: "A", render: () => "x" }]} rows={[]} rowKey={() => "k"} empty="Nothing here yet" caption="c" />);
    expect(m).toContain('data-testid="table-empty"');
    expect(text(m)).toContain("Nothing here yet");
  });
  it("a code list with no items shows the empty row, not a bare header", () => {
    const m = html(<CodeListEditor listKey="x" items={[]} attrs={[]} canWrite />, "bn");
    expect(m).toContain('data-testid="codelist-empty"');
    expect(m).not.toContain('data-testid="codelist-row"');
  });
});

describe("empty states of the grids and editors (kit EmptyNote)", () => {
  const typeLabels = { outlet: "o", cc: "c", distributor: "d", reporting: "r", nto: "n" };
  it("a price grid with no SKU shows the empty note and no table", () => {
    const m = html(<PriceGrid skus={[]} minDate="2026-10-23" canWrite typeLabels={typeLabels} />, "bn");
    expect(m).toContain('data-testid="list-empty"');
    expect(m).not.toContain("<table");
  });
  it("supervisor targets with no officer show the note and still offer to add one", () => {
    const m = html(<SupervisorTargetsEditor month="2026-10" initial={[]} names={{}} canWrite />);
    expect(m).toContain('data-testid="list-empty"');
    expect(m).toContain('data-testid="add-officer"');
  });
  it("an empty package list and an empty sales plan say so", () => {
    expect(html(<PackageListEditor keyName="k" title="T" initial={[]} max={10} canWrite />)).toContain('data-testid="list-empty"');
    expect(html(<SalesPlanEditor zoneId={1} siblingZones={[]} territoryLabel={null} skus={[]} selected={[]} minDate="2026-10-23" canWrite />)).toContain('data-testid="list-empty"');
  });
});

describe("no hardcoded text in the admin portal", () => {
  const files = [...walk("src/app/admin"), ...walk("src/components/admin")];
  it("has files to scan", () => expect(files.length).toBeGreaterThan(100));
  it("no Bengali letters outside the i18n layer", () => {
    const bad = files.filter((f) => /[ঀ-৿]/.test(readFileSync(f, "utf8")));
    expect(bad).toEqual([]);
  });
  it("no English word as an aria-label, placeholder, title or alt literal", () => {
    const bad = files.filter((f) => /\b(?:aria-label|placeholder|title|alt)="[A-Za-z]{3,}/.test(readFileSync(f, "utf8")));
    expect(bad).toEqual([]);
  });
});
