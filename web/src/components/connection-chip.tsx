"use client";
// Connection chip (web-glass s12.5): Live is quiet (dot and word, no container); Offline shows the offline container with
// "data as of HH:MM" (the time of the last successful render), in a polite live region. Never a modal.
import { useEffect, useState, useSyncExternalStore } from "react";
import { useI18n } from "./i18n-provider";
import { StatusChip } from "./kit/chip";

const subscribe = (cb: () => void) => {
  window.addEventListener("online", cb);
  window.addEventListener("offline", cb);
  return () => {
    window.removeEventListener("online", cb);
    window.removeEventListener("offline", cb);
  };
};

export function ConnectionChip() {
  const { t, locale } = useI18n();
  const online = useSyncExternalStore(subscribe, () => navigator.onLine, () => true);
  // "Data as of": the Dhaka time of the last moment the page was known to be online.
  const [asOf, setAsOf] = useState<string>("");
  useEffect(() => {
    if (!online) return;
    const stamp = () => setAsOf(new Intl.DateTimeFormat(locale === "bn" ? "bn-BD-u-nu-beng" : "en-GB", { timeZone: "Asia/Dhaka", hour: "2-digit", minute: "2-digit" }).format(new Date()));
    const id = window.setInterval(stamp, 60_000);
    const first = window.setTimeout(stamp, 0);
    return () => {
      window.clearInterval(id);
      window.clearTimeout(first);
    };
  }, [locale, online]);
  return (
    <div role="status" aria-live="polite" data-testid="connection-chip" data-online={online ? "true" : "false"}>
      {/* Offline before anything was ever loaded has no stamp to quote, so the clause is dropped rather than left dangling. */}
      {online ? <StatusChip tone="quiet">{t("conn.live")}</StatusChip> : <StatusChip tone="offline">{asOf ? t("conn.offline", { at: asOf }) : t("conn.offline_nostamp")}</StatusChip>}
    </div>
  );
}
