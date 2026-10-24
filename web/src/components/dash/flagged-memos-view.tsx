// Flagged memos (D-WEB-FLAGGED-MEMOS-01): a row per memo the server flagged for review, flags as chips, the gross in the house money format.
// Presentational: the page reads the API and passes the outcome, so every state (error, empty, rows, paging) is testable without a server.
import Link from "next/link";
import type { ReactNode } from "react";
import { RetryButton } from "@/components/dash/table-actions";
import { n, takaText } from "@/components/dash/tiles";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { Banner } from "@/components/kit/banner";
import { ButtonLink } from "@/components/kit/button";
import { StatusChip, type ChipTone } from "@/components/kit/chip";
import { EmptyState } from "@/components/kit/empty-state";
import type { ApiOutcome } from "@/lib/api/client";
import { KNOWN_FLAGS, MAX_RANGE_DAYS, type FlaggedMemo, type FlaggedMemoPage, type FlaggedQuery } from "@/lib/dash/flagged-memos";
import { formatBusinessDate, formatDateTime, problemMessage, t, type Locale, type MessageKey } from "@/lib/i18n";

const STATUS_TONE: Record<FlaggedMemo["status"], ChipTone> = { active: "warning", superseded: "quiet", void: "offline" };

export interface FlaggedMemosViewProps {
  locale: Locale;
  today: string;
  query: FlaggedQuery;
  /** The geography pickers (an async server component in the page). */
  scopeFilter: ReactNode;
  /** null when the range was refused before asking (`query.rangeError`). */
  result: ApiOutcome<FlaggedMemoPage> | null;
  firstHref: string | null;
  nextHref: string | null;
  resetHref: string;
}

/** A flag's label: the known four in the language of the page, any other value exactly as the server sent it. */
export function flagLabel(locale: Locale, flag: string): string {
  return (KNOWN_FLAGS as readonly string[]).includes(flag) ? t(locale, `flagged.flag.${flag}` as MessageKey) : flag;
}

export function FlaggedMemosView({ locale, today, query, scopeFilter, result, firstHref, nextHref, resetHref }: FlaggedMemosViewProps) {
  const flagOptions = query.flag && !(KNOWN_FLAGS as readonly string[]).includes(query.flag) ? [...KNOWN_FLAGS, query.flag] : KNOWN_FLAGS;
  const columns: Column<FlaggedMemo>[] = [
    { key: "memo", header: t(locale, "flagged.col.memo"), render: (m) => <span className="tabular-nums" data-testid="flagged-memo-no">{m.memo_no}</span> },
    { key: "date", header: t(locale, "flagged.col.date"), render: (m) => formatBusinessDate(locale, m.business_date) },
    { key: "status", header: t(locale, "flagged.col.status"), render: (m) => <StatusChip tone={STATUS_TONE[m.status]} data-status={m.status}>{t(locale, `flagged.status.${m.status}` as MessageKey)}</StatusChip> },
    { key: "user", header: t(locale, "flagged.col.user"), render: (m) => `${m.user_code} · ${m.user_name}` },
    { key: "outlet", header: t(locale, "flagged.col.outlet"), render: (m) => m.outlet_name },
    { key: "route", header: t(locale, "flagged.col.route"), render: (m) => (m.route_id === null ? <span data-testid="flagged-no-route">{t(locale, "flagged.no_route")}</span> : n(locale, m.route_id)) },
    { key: "gross", header: t(locale, "flagged.col.gross"), align: "right", render: (m) => takaText(locale, m.gross_mtk) },
    {
      key: "flags",
      header: t(locale, "flagged.col.flags"),
      render: (m) => (
        <span className="flex flex-wrap gap-1" data-testid="flagged-flags">
          {m.flags.map((f) => (
            <StatusChip key={f} tone="accent" icon={null} data-flag={f}>
              {flagLabel(locale, f)}
            </StatusChip>
          ))}
        </span>
      ),
    },
    { key: "stored", header: t(locale, "flagged.col.stored"), render: (m) => (m.voided_at ? `${formatDateTime(locale, m.stored_at)} · ${t(locale, "flagged.voided_at", { time: formatDateTime(locale, m.voided_at) })}` : formatDateTime(locale, m.stored_at)) },
  ];
  const dim = (m: FlaggedMemo) => (m.status === "active" ? undefined : { className: "text-secondary", state: m.status });
  return (
    <div className="space-y-4" data-testid="flagged-memos">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-heading text-primary">{t(locale, "menu.flagged_memos")}</h1>
          <p className="text-body text-secondary">{t(locale, "flagged.intro")}</p>
        </div>
      </header>
      <form method="get" className="glass-card flex flex-wrap items-end gap-3 p-(--card-pad)" data-testid="flagged-filter">
        <label className="text-label text-secondary">
          {t(locale, "flagged.from")}
          <input type="date" name="from" defaultValue={query.from} max={today} className="mt-1 block min-h-(--ctl-h) rounded-chip border border-input bg-raised px-3 text-body text-primary focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" />
        </label>
        <label className="text-label text-secondary">
          {t(locale, "flagged.to")}
          <input type="date" name="to" defaultValue={query.to} max={today} className="mt-1 block min-h-(--ctl-h) rounded-chip border border-input bg-raised px-3 text-body text-primary focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" />
        </label>
        <label className="text-label text-secondary">
          {t(locale, "flagged.flag")}
          <select name="flag" defaultValue={query.flag ?? ""} className="mt-1 block min-h-(--ctl-h) rounded-chip border border-input bg-raised px-2 text-body text-primary focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
            <option value="">{t(locale, "common.all")}</option>
            {flagOptions.map((f) => (
              <option key={f} value={f}>
                {flagLabel(locale, f)}
              </option>
            ))}
          </select>
        </label>
        {scopeFilter}
        <div className="flex flex-wrap gap-2">
          <button type="submit" className="inline-flex min-h-(--ctl-h) items-center justify-center rounded-full border border-divider bg-raised px-5 text-body-strong text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
            {t(locale, "common.filter")}
          </button>
          <Link href={resetHref} data-testid="flagged-reset" className="inline-flex min-h-(--ctl-h) items-center rounded-full px-4 text-body-strong text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
            {t(locale, "dashboard.filter.reset")}
          </Link>
        </div>
      </form>
      {query.rangeError ? (
        <Banner tone="warning" role="alert" testId="flagged-range-error">
          {query.rangeError === "order" ? t(locale, "flagged.range.order") : t(locale, "flagged.range.too_long", { days: n(locale, MAX_RANGE_DAYS) })}
        </Banner>
      ) : result === null ? null : !result.ok ? (
        <Banner tone="danger" role="alert" testId="flagged-error" action={result.status === 403 ? undefined : <RetryButton msg={t(locale, "table.retry")} />}>
          {problemMessage(locale, result.problem.code)}
        </Banner>
      ) : result.data.items.length === 0 ? (
        <EmptyState testId="flagged-empty" icon="check-circle" title={t(locale, "flagged.empty.title")} body={t(locale, "flagged.empty.body")} />
      ) : (
        <>
          <DataTable columns={columns} rows={result.data.items} rowKey={(m) => m.memo_client_uuid} empty={t(locale, "common.empty")} caption={t(locale, "menu.flagged_memos")} rowProps={dim} />
          <nav className="flex flex-wrap items-center justify-between gap-2" aria-label={t(locale, "flagged.paging")} data-testid="flagged-paging">
            {firstHref ? <ButtonLink href={firstHref} variant="secondary" testId="flagged-first">{t(locale, "flagged.first_page")}</ButtonLink> : <span />}
            {nextHref ? <ButtonLink href={nextHref} variant="secondary" testId="flagged-next">{t(locale, "common.next")}</ButtonLink> : <span className="text-caption text-secondary" data-testid="flagged-end">{t(locale, "flagged.end")}</span>}
          </nav>
        </>
      )}
    </div>
  );
}
