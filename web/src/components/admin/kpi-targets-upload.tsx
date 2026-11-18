"use client";
import { SheetImport, type SheetImportConfig } from "./sheet-import";
import { kpiTemplateCells, parseKpiSheet, SECTION_MEMBER, type KpiKind, type KpiPreview } from "@/lib/admin/kpi-targets";
import { useI18n } from "@/components/i18n-provider";
import type { MessageKey } from "@/lib/i18n/messages-en";

type P = KpiPreview & { rows?: number };

/** KPI targets: the shared Excel flow with the parser and operations of one section (brand, month, programme, campaign, campaign-sku). */
export function KpiTargetsUpload({ kind, currentMonth }: { kind: KpiKind; currentMonth: string }) {
  const { t } = useI18n();
  const member = SECTION_MEMBER[kind];
  const cfg: SheetImportConfig<P> = {
    tid: "kt",
    parse: (cells) => parseKpiSheet(kind, cells, currentMonth),
    validateOp: "kpi-targets.validate",
    commitOp: "kpi-targets.commit",
    rowsMember: member,
    template: () => kpiTemplateCells(kind, currentMonth),
    templateFile: `kpi-targets-${kind}-template.xlsx`,
    columnsText: t(`kt.cols.${kind}` as MessageKey),
    columnLabel: (c) => t(`kt.col.${c}` as MessageKey),
    problemKey: (c) => `kt.problem.${c}`,
    // the preview counts rows per section; the shared flow reads `rows` only for the one-array uploads
    summaryText: (p, number) => t(`kt.summary.${kind}` as MessageKey, { n: number(p[member]) }),
    committedKey: "kt.committed",
  };
  return <SheetImport cfg={cfg} />;
}
