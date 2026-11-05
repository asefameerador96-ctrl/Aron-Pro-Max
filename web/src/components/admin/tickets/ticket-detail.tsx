// One ticket at the desk (TK-16; screenshot 14 for the words): "INC… · Tickets", the title, the priority and status chips and the
// age, the facts, the reporter's description and photos (TK-14), the thread with "Support" for desk entries, the closed note once
// the ticket is no longer open, and the desk's actions. Server rendered; the actions are the client DeskPanel.
import Link from "next/link";
import type { ReactNode } from "react";
import type { SupportTicketDetail } from "@/contract/types";
import { Card, PageHeading } from "@/components/admin/kit/page";
import { Banner } from "@/components/kit/banner";
import { Icon } from "@/components/kit/icons";
import { formatBusinessDate, formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { ticketAge } from "@/lib/national/fmt";
import { DeskPanel } from "./desk-panel";
import type { ResolveCode } from "./resolve-form";
import { BASE, issueLabel, PriorityBadge, StatusBadge, ticketNo } from "./ticket-list";
import { isOpen } from "./ticket-rules";
import { PhotoStrip, TicketThread } from "./ticket-thread";

export interface TicketDetailProps {
  locale: Locale;
  detail: SupportTicketDetail;
  photos: Readonly<Record<string, string>>;
  nowMs: number;
  /** null: the caller may read but not act (read-only grant); else the desk panel's inputs. */
  desk: { codes: readonly ResolveCode[]; codesFallback: boolean } | null;
}

function Fact({ label, children, testId }: { label: string; children: ReactNode; testId?: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-label text-secondary">{label}</dt>
      <dd className="text-body text-primary [overflow-wrap:anywhere]" data-testid={testId}>
        {children}
      </dd>
    </div>
  );
}

export function TicketDetailView({ locale, detail, photos, nowMs, desk }: TicketDetailProps) {
  const tk = detail.ticket;
  const when = (iso: string | null) => (iso ? formatDateTime(locale, iso) : "—");
  return (
    <div className="space-y-(--gap)" data-testid="ticket-detail" data-status={tk.status} data-version={tk.version}>
      <Link href={BASE} className="inline-flex min-h-(--ctl-h) items-center gap-1 text-body-strong text-accent hover:underline" data-testid="ticket-back">
        <Icon name="chevron-left" size={18} />
        {t(locale, "tickets.back")}
      </Link>
      <PageHeading
        title={tk.title}
        caption={
          <span className="flex flex-wrap items-center justify-between gap-2">
            <span data-testid="ticket-no">
              {ticketNo(locale, tk)} · {t(locale, "tickets.category")}
            </span>
            <span>{ticketAge(locale, nowMs, Date.parse(tk.captured_at))}</span>
          </span>
        }
        actions={
          <span className="flex flex-wrap gap-2">
            <PriorityBadge locale={locale} priority={tk.priority} />
            <StatusBadge locale={locale} status={tk.status} />
          </span>
        }
      />
      <div className="grid gap-(--gap) xl:grid-cols-[1fr_2fr]">
        <Card title={t(locale, "tickets.facts")} testId="ticket-facts">
          <dl className="grid gap-3 sm:grid-cols-2 xl:grid-cols-1">
            <Fact label={t(locale, "tickets.field.issue")}>{issueLabel(locale, tk)}</Fact>
            <Fact label={t(locale, "tickets.field.requester")} testId="requester">
              {tk.requester.full_name} · {tk.requester.username} · {t(locale, `role.${tk.requester.role}` as MessageKey)}
            </Fact>
            {tk.affected_username ? <Fact label={t(locale, "tickets.field.affected")}>{tk.affected_username}</Fact> : null}
            <Fact label={t(locale, "tickets.field.source")}>{t(locale, `tickets.source.${tk.source}` as MessageKey)}</Fact>
            <Fact label={t(locale, "tickets.field.business_date")}>{formatBusinessDate(locale, tk.business_date)}</Fact>
            <Fact label={t(locale, "tickets.field.captured")}>{when(tk.captured_at)}</Fact>
            <Fact label={t(locale, "tickets.field.first_response")}>{when(tk.first_response_at)}</Fact>
            {tk.assignee_name ? <Fact label={t(locale, "tickets.field.assignee")}>{tk.assignee_name}</Fact> : null}
            {tk.resolved_at ? <Fact label={t(locale, "tickets.field.resolved_at")}>{when(tk.resolved_at)}</Fact> : null}
            {tk.resolution_code ? (
              <Fact label={t(locale, "tickets.field.resolution")} testId="resolution">
                {tk.resolution_label_en ?? tk.resolution_code}
                {tk.resolution_notes ? <span className="mt-1 block whitespace-pre-wrap text-secondary">{tk.resolution_notes}</span> : null}
              </Fact>
            ) : null}
            {tk.closed_at ? <Fact label={t(locale, "tickets.field.closed_at")}>{when(tk.closed_at)}</Fact> : null}
            {tk.cancelled_at ? <Fact label={t(locale, "tickets.field.cancelled_at")}>{when(tk.cancelled_at)}</Fact> : null}
            {tk.cancel_reason ? <Fact label={t(locale, "tickets.field.cancel_reason")} testId="cancel-reason">{tk.cancel_reason}</Fact> : null}
            <Fact label={t(locale, "tickets.field.photos")}>{formatNumber(locale, tk.attachment_count)}</Fact>
          </dl>
        </Card>
        <div className="space-y-(--gap)">
          <Card title={t(locale, "tickets.field.description")} testId="ticket-description">
            {tk.description ? <p className="whitespace-pre-wrap text-body text-primary [overflow-wrap:anywhere]">{tk.description}</p> : <p className="text-body text-secondary">{t(locale, "tickets.no_description")}</p>}
            <PhotoStrip locale={locale} ids={detail.attachments} photos={photos} />
          </Card>
          <Card title={t(locale, "tickets.thread")} testId="ticket-conversation">
            <TicketThread locale={locale} events={detail.events} photos={photos} />
            {isOpen(tk.status) ? null : (
              <p className="border-t border-divider pt-3 text-body text-secondary" data-testid="comments-closed">
                {t(locale, `tickets.closed.${tk.status === "resolved" || tk.status === "closed" || tk.status === "cancelled" ? tk.status : "closed"}` as MessageKey)}
              </p>
            )}
          </Card>
          <Card title={t(locale, "tickets.desk")} testId="ticket-desk">
            {desk ? (
              <DeskPanel ticketUuid={tk.ticket_uuid} version={tk.version} status={tk.status} priority={tk.priority} codes={desk.codes} codesFallback={desk.codesFallback} />
            ) : (
              <Banner tone="info" role="note" testId="desk-read-only">
                {t(locale, "tickets.desk.read_only")}
              </Banner>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}
