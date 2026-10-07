// F-ADM-042 Config page P5: change requests with the diff and blast radius; approve, reject or withdraw (two-person for risk 3).
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { OpInline } from "@/components/admin/kit/op-inline";
import { NextLink, PageHeading } from "@/components/admin/kit/page";
import type { ConfigChange } from "@/lib/admin/types";
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { ChangesTable } from "./changes-table";

const STATUSES = ["pending_approval", "scheduled", "applied", "rejected", "cancelled", "expired", "reverted"] as const;

export function ChangesView({ locale, rows, status, nextHref, userId, canDecide }: { locale: Locale; rows: ConfigChange[]; status: string; nextHref: string | null; userId: number; canDecide: boolean }) {
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "cfgp5.title")} intro={t(locale, "cfgp5.scheduled_note")} />
      <FilterBar
        controls={[{ param: "status", label: t(locale, "cfgp5.filter.status"), kind: "enum", value: status, options: STATUSES.map((s) => ({ value: s, label: t(locale, `cfgc.status.${s}` as MessageKey).replace(/\.$/, "") })) }]}
        applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/config/changes"
      />
      <ChangesTable
        locale={locale}
        rows={rows}
        caption={t(locale, "cfgp5.title")}
        actions={(c) => {
          const b = c.blast_radius;
          return (
            <div className="space-y-2" data-testid={`change-${c.change_id}`}>
              <p className="text-xs text-slate-600">
                {t(locale, "cfgp5.blast")}: {t(locale, "cfgp5.blast.line", { zones: formatNumber(locale, b.zones), routes: formatNumber(locale, b.routes), outlets: formatNumber(locale, b.outlets), devices: formatNumber(locale, b.devices) })}
              </p>
              {c.risk_class >= 3 ? <p className="text-xs text-amber-700">{t(locale, "cfgp5.two_person")}</p> : null}
              {c.status === "pending_approval" && canDecide ? (
                c.requested_by === userId ? (
                  <>
                    <p className="text-xs text-slate-600">{t(locale, "cfgp5.own")}</p>
                    <OpInline op="config.decide" params={{ change_id: String(c.change_id) }} body={{ decision: "cancel" }} label={t(locale, "cfgp5.cancel")} successKey="cfgp5.done" testId={`cancel-${c.change_id}`} />
                  </>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    <OpInline op="config.decide" params={{ change_id: String(c.change_id) }} body={{ decision: "approve" }} label={t(locale, "cfgp5.approve")} successKey="cfgp5.done" testId={`approve-${c.change_id}`} />
                    <OpInline op="config.decide" params={{ change_id: String(c.change_id) }} body={{ decision: "reject" }} label={t(locale, "cfgp5.reject")} danger successKey="cfgp5.done" testId={`reject-${c.change_id}`} />
                  </div>
                )
              ) : null}
            </div>
          );
        }}
      />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
    </div>
  );
}
