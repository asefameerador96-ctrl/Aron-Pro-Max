import { notFound } from "next/navigation";
import { Forbidden } from "@/components/forbidden";
import { onApiFailure } from "@/components/admin/crud/pages";
import { TicketDetailView } from "@/components/admin/tickets/ticket-detail";
import { deskContext, getTicket, photoUrls, resolutionCodes } from "@/components/admin/tickets/server";
import { UUID_V4 } from "@/components/admin/tickets/ticket-rules";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { serverNowMs } from "@/lib/admin/time";
import { businessDate } from "@/lib/i18n";

// One ticket at the desk (TK-16, TK-14): the thread, the photos and the desk's actions. A malformed UUID is a 404 before any call.
export default async function TicketPage({ params }: { params: Promise<{ ticket_uuid: string }> }) {
  const [{ ticket_uuid }, locale, session] = await Promise.all([params, getLocale(), requireSession()]);
  if (!UUID_V4.test(ticket_uuid)) notFound();
  const ctx = await deskContext(session.at, session.user.role);
  if (!ctx.desk) return <Forbidden locale={locale} />;
  const [d, codes] = await Promise.all([getTicket(session.at, ticket_uuid), ctx.canAct ? resolutionCodes(session.at, businessDate()) : Promise.resolve(null)]);
  if (!d.ok) return onApiFailure(d.status, d.problem, locale);
  const photos = await photoUrls(session.at, [...d.data.attachments, ...d.data.events.flatMap((e) => e.attachments)]);
  const desk = codes ? { codes: codes.options.map((o) => ({ code: o.code, label: locale === "bn" && o.label_bn ? o.label_bn : o.label_en })), codesFallback: codes.fallback } : null;
  return <TicketDetailView locale={locale} detail={d.data} photos={photos} nowMs={serverNowMs()} desk={desk} />;
}
