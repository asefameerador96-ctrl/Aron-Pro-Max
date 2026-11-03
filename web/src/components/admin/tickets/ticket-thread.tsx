// A ticket's thread, oldest first (D-TSO-MAN-21; screenshot 14): a desk entry is always shown as "Support", never by name, in its
// own bubble with the headset mark; the reporter's entries carry the reporter's name. Status, priority and assignment entries are
// one-line notes; a comment, a reply or the resolution message keeps its line breaks. Photos come as short-lived read URLs (TK-14).
import type { SupportTicketEvent, SupportTicketStatus } from "@/contract/types";
import { Icon } from "@/components/kit/icons";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

const statusLabel = (locale: Locale, s: SupportTicketStatus | null): string => (s ? t(locale, `tickets.status.${s}` as MessageKey) : "—");

/** The name an entry shows: "Support" for every desk actor (D-TSO-MAN-21), else the reporter's name the server sends. */
export function actorName(locale: Locale, ev: Pick<SupportTicketEvent, "actor_is_support" | "actor_display">): string {
  return ev.actor_is_support ? t(locale, "tickets.support") : ev.actor_display;
}

function noteOf(locale: Locale, ev: SupportTicketEvent): string | null {
  switch (ev.kind) {
    case "created":
      return t(locale, "tickets.event.created");
    case "close_review":
      return t(locale, "tickets.event.close_review");
    case "status_change":
      return t(locale, "tickets.event.status_change", { from: statusLabel(locale, ev.from_status), to: statusLabel(locale, ev.to_status) });
    case "priority_change":
      return t(locale, "tickets.event.priority_change");
    case "assign":
      return t(locale, "tickets.event.assign");
    default:
      return null; // comment and resolution: the body is the message
  }
}

export function TicketThread({ locale, events, photos }: { locale: Locale; events: readonly SupportTicketEvent[]; photos: Readonly<Record<string, string>> }) {
  if (events.length === 0) return <p className="text-body text-secondary" data-testid="thread-empty">{t(locale, "tickets.thread.empty")}</p>;
  return (
    <ol className="space-y-3" data-testid="ticket-thread">
      {events.map((ev) => {
        const support = ev.actor_is_support;
        const note = noteOf(locale, ev);
        return (
          <li key={ev.event_uuid} className={`flex gap-3 ${support ? "" : "flex-row-reverse"}`} data-kind={ev.kind} data-support={support ? "1" : "0"} data-testid={`event-${ev.event_uuid}`}>
            <span className={`mt-1 inline-flex size-9 shrink-0 items-center justify-center rounded-full ${support ? "bg-warning-container text-warning-on-container" : "bg-accent-container text-accent-on-container"}`} aria-hidden="true">
              <Icon name={support ? "team" : "user"} size={18} />
            </span>
            <div className={`min-w-0 max-w-[48rem] flex-1 rounded-card border p-3 ${support ? "border-warning-container bg-raised" : "border-divider bg-raised"}`}>
              <p className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="text-body-strong text-primary" data-testid="actor">{actorName(locale, ev)}</span>
                <time dateTime={ev.created_at} className="text-caption text-secondary">{formatDateTime(locale, ev.created_at)}</time>
              </p>
              {note ? <p className="mt-1 text-caption text-secondary">{note}</p> : null}
              {ev.body ? <p className="mt-2 whitespace-pre-wrap text-body text-primary [overflow-wrap:anywhere]" data-testid="body">{ev.body}</p> : null}
              {ev.attachments.length > 0 ? (
                <div className="mt-2 space-y-1">
                  <p className="text-caption text-secondary">{t(locale, "tickets.event.photos", { n: formatNumber(locale, ev.attachments.length) })}</p>
                  <PhotoStrip locale={locale} ids={ev.attachments} photos={photos} />
                </div>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/** Thumbnails of the photos whose read URL could be fetched; each opens full size in a new tab. */
export function PhotoStrip({ locale, ids, photos }: { locale: Locale; ids: readonly string[]; photos: Readonly<Record<string, string>> }) {
  const shown = ids.filter((id) => photos[id]);
  if (shown.length === 0) return null;
  return (
    <ul className="flex flex-wrap gap-2" data-testid="photos">
      {shown.map((id, i) => (
        <li key={id}>
          <a href={photos[id]} target="_blank" rel="noopener noreferrer" className="block rounded-chip focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
            {/* eslint-disable-next-line @next/next/no-img-element -- a compressed field photo from the storage origin (short-lived SAS URL); next/image would proxy it */}
            <img src={photos[id]} alt={`${t(locale, "tickets.field.photos")} ${formatNumber(locale, i + 1)}`} width={96} height={96} className="h-24 w-24 rounded-chip border border-divider object-cover" />
          </a>
        </li>
      ))}
    </ul>
  );
}
