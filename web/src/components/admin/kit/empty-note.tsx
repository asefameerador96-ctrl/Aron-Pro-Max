"use client";
// The empty state of a list that is not a DataTable (grids, editors, small lists): same box and words as the table's own, from the i18n layer.
import { useI18n } from "@/components/i18n-provider";

export function EmptyNote({ testId = "list-empty" }: { testId?: string }) {
  const { t } = useI18n();
  return (
    <p data-testid={testId} className="glass-card border-dashed p-6 text-center text-body text-secondary">
      {t("common.empty")}
    </p>
  );
}
