// Channel label for the dashboard `by_channel` rows (ChannelVolume.code is a free string): the server's name when it sends one, the message
// for the six known channels and for `unknown` (memos with no channel, route-only dashboards from dw.v_route_day_channel), and any
// other code exactly as given. Never a raw message key, and the row stays in the totals (the caller sums every row).
import { t, type Locale, type MessageKey } from "@/lib/i18n";

const KNOWN = ["GT", "DCC", "Astha", "RCC", "MT", "HoReCa", "unknown"] as const;

export function channelLabel(locale: Locale, code: string, name?: string | null): string {
  if (name) return name;
  return (KNOWN as readonly string[]).includes(code) ? t(locale, `channel.${code}` as MessageKey) : code;
}
