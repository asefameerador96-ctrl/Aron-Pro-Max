"use client";
import { SheetImport, type SheetImportConfig } from "./sheet-import";
import { HEADERS, parseTargetSheet, templateCells, type TargetPreview } from "@/lib/admin/route-targets";
import { useI18n } from "@/components/i18n-provider";

/** Route monthly targets (D-TARGET-MIN-01): the shared Excel flow with the target parser and operations. */
export function RouteTargetsUpload({ currentMonth }: { currentMonth: string }) {
  const { t } = useI18n();
  const cfg: SheetImportConfig<TargetPreview> = {
    tid: "rt",
    parse: (cells) => parseTargetSheet(cells, currentMonth),
    validateOp: "route-targets.validate",
    commitOp: "route-targets.commit",
    template: () => templateCells(currentMonth),
    templateFile: "route-targets-template.xlsx",
    columnsText: `${t("rt.columns")} ${Object.values(HEADERS).join(", ")}`,
    columnLabel: (c) => t(`rt.col.${c}` as "rt.col.route"),
    problemLabel: (c) => t(`rt.problem.${c}` as "rt.problem.invalid"),
    summaryText: (p, number) => t("rt.summary", { rows: number(p.rows), routes: number(p.routes), skus: number(p.skus), months: p.months.join(", "), total: number(p.total_qty_base) }),
    committedKey: "rt.committed",
  };
  return <SheetImport cfg={cfg} />;
}
