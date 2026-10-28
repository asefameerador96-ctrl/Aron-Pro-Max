// The support console's list (TK-16; screenshots 11 to 13 for the words and figures): the counts with the resolution status, the
// status tabs with their counts, search and filters in the URL, and one row per ticket. Server rendered from the contract's
// SupportTicketPage and SupportTicketSummary; every figure goes through lib/national/fmt (whole percent once from the counts,
// R-F-14; the age of D-TSO-MAN-25).
import Link from "next/link";
import type { SupportTicket, SupportTicketPriority, SupportTicketStatus, SupportTicketSummary } from "@/contract/types";
import { Card, PageHeading, qs } from "@/components/admin/kit/page";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar, type FilterControl } from "@/components/admin/kit/filter-bar";
import { Banner } from "@/components/kit/banner";
import { StatusChip, type ChipTone } from "@/components/kit/chip";
import { Meter } from "@/components/kit/meter";
import { Icon } from "@/components/kit/icons";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { pctText, ticketAge, ticketCounts } from "@/lib/national/fmt";
import type { CodeOption, TicketQuery } from "./server";
import { TICKET_PRIORITIES, TICKET_STATUSES } from "./ticket-rules";

export const BASE = "/admin/tickets";

const STATUS_TONE: Record<SupportTicketStatus, ChipTone> = { new: "accent", in_progress: "warning", resolved: "success", closed: "quiet", cancelled: "offline" };
const PRIORITY_TONE: Record<SupportTicketPriority, ChipTone> = { p1: "danger", p2: "warning", p3: "accent", p4: "quiet" };

export function StatusBadge({ locale, status }: { locale: Locale; status: SupportTicketStatus }) {
  return (
    <StatusChip tone={STATUS_TONE[status]} data-status={status}>
      {t(locale, `tickets.status.${status}` as MessageKey)}
    </StatusChip>
  );
}

export function PriorityBadge({ locale, priority }: { locale: Locale; priority: SupportTicketPriority }) {
  return (
    <StatusChip tone={PRIORITY_TONE[priority]} icon={null} data-priority={priority}>
      {t(locale, `tickets.priority.${priority}` as MessageKey)}
    </StatusChip>
  );
}

/** The issue type as the reader's language has it: the Bangla label once AKTCL supplies it, else the English one (V0027 rule). */
export const issueLabel = (locale: Locale, tk: Pick<SupportTicket, "issue_type_label_en" | "issue_type_label_bn">): string => (locale === "bn" && tk.issue_type_label_bn ? tk.issue_type_label_bn : tk.issue_type_label_en);
export const ticketNo = (locale: Locale, tk: Pick<SupportTicket, "ticket_no">): string => tk.ticket_no ?? t(locale, "tickets.unnumbered");

/** The list's query string, without the page cursor (a tab or a filter starts from the first page). */
function hrefWith(q: TicketQuery, over: Partial<Record<keyof TicketQuery, string | undefined>>): string {
  const base = { status: q.status, q: q.q, priority: q.priority, issue_type_code: q.issue_type_code, from: q.from, to: q.to };
  return `${BASE}${qs({ ...base, ...over })}`;
}

function SummaryCards({ locale, s }: { locale: Locale; s: SupportTicketSummary }) {
  const c = ticketCounts(s);
  const n = (v: number) => formatNumber(locale, v);
  const cards: { key: string; label: string; value: number; href: string; icon: "inbox" | "clock" | "warning" | "check-circle" | "lock" | "close" }[] = [
    { key: "total", label: t(locale, "tickets.summary.total"), value: s.total, href: BASE, icon: "inbox" },
    { key: "new", label: t(locale, "tickets.status.new"), value: s.new, href: `${BASE}?status=new`, icon: "clock" },
    { key: "in_progress", label: t(locale, "tickets.status.in_progress"), value: s.in_progress, href: `${BASE}?status=in_progress`, icon: "warning" },
    { key: "resolved", label: t(locale, "tickets.status.resolved"), value: s.resolved, href: `${BASE}?status=resolved`, icon: "check-circle" },
    { key: "closed", label: t(locale, "tickets.status.closed"), value: s.closed, href: `${BASE}?status=closed`, icon: "lock" },
    { key: "cancelled", label: t(locale, "tickets.status.cancelled"), value: s.cancelled, href: `${BASE}?status=cancelled`, icon: "close" },
  ];
  const resolvedText = pctText(locale, c.resolvedPct, 0);
  const openText = pctText(locale, c.openPct, 0);
  return (
    <div className="grid gap-(--gap) lg:grid-cols-[2fr_1fr]">
      <div className="grid grid-cols-2 gap-(--gap) sm:grid-cols-3" data-testid="ticket-counts">
        {cards.map((k) => (
          <Link key={k.key} href={k.href} data-testid={`count-${k.key}`} data-value={k.value} className="glass-card flex min-h-28 flex-col gap-2 p-(--card-pad) text-primary hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
            <Icon name={k.icon} className="text-accent" />
            <span className="text-display tabular-nums">{n(k.value)}</span>
            <span className="text-label text-secondary">{k.label}</span>
          </Link>
        ))}
      </div>
      <Card title={t(locale, "tickets.summary.resolution")} testId="ticket-resolution">
        <div className="space-y-4">
          <div className="space-y-1.5">
            <p className="flex items-center justify-between gap-2 text-body-strong">
              <span>{t(locale, "tickets.status.resolved")}</span>
              <span className="tabular-nums text-success-on-container" data-testid="resolved-pct">{resolvedText}</span>
            </p>
            <Meter pct={c.resolvedPct} valueText={resolvedText} label={t(locale, "tickets.status.resolved")} />
            <p className="text-caption text-secondary">{t(locale, "tickets.summary.resolved_of", { n: s.resolved, total: s.total })}</p>
          </div>
          <div className="space-y-1.5">
            <p className="flex items-center justify-between gap-2 text-body-strong">
              <span>{t(locale, "tickets.summary.open")}</span>
              <span className="tabular-nums text-success-on-container" data-testid="open-pct">{openText}</span>
            </p>
            <Meter pct={c.openPct} valueText={openText} label={t(locale, "tickets.summary.open")} />
            <p className="text-caption text-secondary">{t(locale, "tickets.summary.open_of", { n: c.open, total: s.total })}</p>
          </div>
        </div>
      </Card>
    </div>
  );
}

function Tabs({ locale, q, s }: { locale: Locale; q: TicketQuery; s: SupportTicketSummary | null }) {
  const count = (v: number | undefined) => (v === undefined ? "" : ` (${formatNumber(locale, v)})`);
  const tabs: { id: SupportTicketStatus | "all"; label: string; n: number | undefined }[] = [
    { id: "all", label: t(locale, "tickets.tab.all"), n: s?.total },
    ...TICKET_STATUSES.map((st) => ({ id: st, label: t(locale, `tickets.status.${st}` as MessageKey), n: s?.[st] })),
  ];
  return (
    <nav aria-label={t(locale, "tickets.tabs")} className="seg" data-testid="ticket-tabs">
      {tabs.map((tab) => {
        const current = (q.status ?? "all") === tab.id;
        return (
          <Link key={tab.id} href={hrefWith(q, { status: tab.id === "all" ? undefined : tab.id })} aria-current={current ? "page" : undefined} data-testid={`tab-${tab.id}`}>
            {current ? <Icon name="check" size={16} /> : null}
            {tab.label}
            {count(tab.n)}
          </Link>
        );
      })}
    </nav>
  );
}

export interface TicketListProps {
  locale: Locale;
  query: TicketQuery;
  items: SupportTicket[];
  nextCursor: string | null;
  summary: SupportTicketSummary | null;
  issueTypes: CodeOption[] | null;
  /** Server time of the render, for the age column. */
  nowMs: number;
  /** The list read failed: its problem text (the counts may still show). */
  listError?: string;
}

export function TicketListView({ locale, query, items, nextCursor, summary, issueTypes, nowMs, listError }: TicketListProps) {
  const controls: FilterControl[] = [
    { param: "q", label: t(locale, "tickets.search"), kind: "search", value: query.q ?? "" },
    { param: "priority", label: t(locale, "tickets.filter.priority"), kind: "enum", value: query.priority ?? "", options: TICKET_PRIORITIES.map((p) => ({ value: p, label: t(locale, `tickets.priority.${p}` as MessageKey) })) },
    ...(issueTypes && issueTypes.length > 0 ? [{ param: "issue_type_code", label: t(locale, "tickets.filter.issue"), kind: "enum" as const, value: query.issue_type_code ?? "", options: issueTypes.map((o) => ({ value: o.code, label: locale === "bn" && o.label_bn ? o.label_bn : o.label_en })) }] : []),
    { param: "from", label: t(locale, "tickets.filter.from"), kind: "date", value: query.from ?? "" },
    { param: "to", label: t(locale, "tickets.filter.to"), kind: "date", value: query.to ?? "" },
  ];
  const columns: Column<SupportTicket>[] = [
    {
      key: "ticket",
      header: t(locale, "tickets.col.ticket"),
      render: (tk) => (
        <Link href={`${BASE}/${tk.ticket_uuid}`} className="text-body-strong text-accent hover:underline" data-testid={`ticket-${tk.ticket_uuid}`}>
          <span className="whitespace-nowrap tabular-nums">{ticketNo(locale, tk)}</span>
          <span className="block text-caption text-secondary">{issueLabel(locale, tk)}</span>
        </Link>
      ),
    },
    { key: "title", header: t(locale, "tickets.col.title"), render: (tk) => tk.title },
    {
      key: "requester",
      header: t(locale, "tickets.col.requester"),
      render: (tk) => (
        <span>
          {tk.requester.full_name}
          <span className="block text-caption text-secondary">
            {tk.requester.username} · {t(locale, `role.${tk.requester.role}` as MessageKey)}
          </span>
        </span>
      ),
    },
    { key: "priority", header: t(locale, "tickets.col.priority"), render: (tk) => <PriorityBadge locale={locale} priority={tk.priority} /> },
    { key: "status", header: t(locale, "tickets.col.status"), render: (tk) => <StatusBadge locale={locale} status={tk.status} /> },
    { key: "age", header: t(locale, "tickets.col.age"), render: (tk) => <span className="whitespace-nowrap" title={formatDateTime(locale, tk.captured_at)}>{ticketAge(locale, nowMs, Date.parse(tk.captured_at))}</span> },
    { key: "updated", header: t(locale, "tickets.col.updated"), render: (tk) => <span className="whitespace-nowrap">{formatDateTime(locale, tk.updated_at)}</span> },
  ];
  return (
    <div className="space-y-(--gap)" data-testid="ticket-console">
      <PageHeading title={t(locale, "tickets.title")} intro={t(locale, "tickets.intro")} caption={summary ? t(locale, "tickets.as_of", { time: formatDateTime(locale, summary.as_of) }) : undefined} />
      {summary ? <SummaryCards locale={locale} s={summary} /> : <Banner tone="warning" role="status" testId="summary-failed">{t(locale, "tickets.summary.failed")}</Banner>}
      <Tabs locale={locale} q={query} s={summary} />
      <FilterBar controls={controls} applyLabel={t(locale, "tickets.filter.apply")} clearLabel={t(locale, "tickets.filter.clear")} allLabel={t(locale, "tickets.filter.any")} clearHref={hrefWith({}, { status: query.status })}>
        {query.status ? <input type="hidden" name="status" value={query.status} /> : null}
      </FilterBar>
      <p className="text-caption text-secondary">{t(locale, "tickets.search.hint")}</p>
      {query.qTooShort ? <Banner tone="info" role="status" testId="search-too-short">{t(locale, "tickets.search.too_short")}</Banner> : null}
      {listError ? (
        <Banner tone="danger" role="alert" testId="list-error">{listError}</Banner>
      ) : (
        <DataTable columns={columns} rows={items} rowKey={(tk) => tk.ticket_uuid} empty={t(locale, "tickets.empty")} caption={t(locale, "tickets.list")} rowProps={(tk) => ({ state: tk.status })} />
      )}
      <div className="flex flex-wrap gap-3">
        {query.cursor ? (
          <Link href={hrefWith(query, {})} className="inline-flex min-h-(--ctl-h) items-center gap-1 text-body-strong text-accent hover:underline" data-testid="first-page">
            <Icon name="chevrons-left" size={18} />
            {t(locale, "tickets.first")}
          </Link>
        ) : null}
        {nextCursor ? (
          <Link href={`${hrefWith(query, {})}${hrefWith(query, {}).includes("?") ? "&" : "?"}cursor=${encodeURIComponent(nextCursor)}`} className="inline-flex min-h-(--ctl-h) items-center gap-1 text-body-strong text-accent hover:underline" data-testid="next-page">
            {t(locale, "tickets.next")}
            <Icon name="chevron-right" size={18} />
          </Link>
        ) : null}
      </div>
    </div>
  );
}
