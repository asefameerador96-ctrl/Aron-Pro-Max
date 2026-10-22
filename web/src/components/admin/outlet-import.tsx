"use client";
import { SheetImport, type SheetImportConfig } from "./sheet-import";
import { OUTLET_HEADERS, outletTemplateCells, parseOutletSheet, type OutletPreview } from "@/lib/admin/outlet-import";
import { useI18n } from "@/components/i18n-provider";

/** Outlet import (channel mandatory): the shared Excel flow with the outlet parser and operations. */
export function OutletImport() {
  const { t } = useI18n();
  const cfg: SheetImportConfig<OutletPreview> = {
    tid: "oi",
    parse: (cells) => parseOutletSheet(cells),
    validateOp: "outlet-import.validate",
    commitOp: "outlet-import.commit",
    template: outletTemplateCells,
    templateFile: "outlets-template.xlsx",
    columnsText: `${t("oi.columns")} ${OUTLET_HEADERS.join(", ")}`,
    columnLabel: (c) => t(`oi.col.${c}` as "oi.col.name"),
    problemKey: (c) => `oi.problem.${c}`,
    summaryText: (p, number) => t("oi.summary", { rows: number(p.rows), creates: number(p.creates), updates: number(p.updates), channels: Object.entries(p.by_channel).map(([k, v]) => `${k} ${number(v)}`).join(", ") }),
    committedKey: "oi.committed",
  };
  return <SheetImport cfg={cfg} />;
}
