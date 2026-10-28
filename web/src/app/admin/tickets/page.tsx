import { Forbidden } from "@/components/forbidden";
import { onApiFailure } from "@/components/admin/crud/pages";
import type { SearchParams } from "@/components/admin/kit/page";
import { TicketListView } from "@/components/admin/tickets/ticket-list";
import { codeOptions, deskContext, listTickets, parseTicketQuery, ticketSummary } from "@/components/admin/tickets/server";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { serverNowMs } from "@/lib/admin/time";
import { businessDate, problemMessage } from "@/lib/i18n";

// The support console (TK-16, D-TSO-MAN-22): only the support desk (Me.support_desk) works tickets; the API scopes the list (the
// desk sees every ticket) and refuses anyone else. Filters live in the URL, so a view can be shared and survives a reload.
export default async function TicketsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const ctx = await deskContext(session.at, session.user.role);
  if (!ctx.desk) return <Forbidden locale={locale} />;
  const query = parseTicketQuery(sp);
  const [list, summary, issueTypes] = await Promise.all([listTickets(session.at, query), ticketSummary(session.at), codeOptions(session.at, "ticket_issue_type", businessDate())]);
  if (!list.ok && (list.status === 401 || list.status === 403)) return onApiFailure(list.status, list.problem, locale);
  return (
    <TicketListView
      locale={locale}
      query={query}
      items={list.ok ? list.data.items : []}
      nextCursor={list.ok ? list.data.next_cursor : null}
      summary={summary.ok ? summary.data : null}
      issueTypes={issueTypes}
      nowMs={serverNowMs()}
      listError={list.ok ? undefined : problemMessage(locale, list.problem.code)}
    />
  );
}
