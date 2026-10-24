"use client";
// Client controls shared by the dashboard tables and tiles: CSV download of the rows on screen, print, retry, and the
// auto-refresh that never runs faster than 30 s and stays quiet while the tab is hidden.
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, useTransition } from "react";
import { Button } from "@/components/kit/button";
import { toCsv } from "@/lib/dash/csv";

export const REFRESH_MS = 30_000;

export function CsvButton({ msg, filename, header, rows }: { msg: string; filename: string; header: string[]; rows: string[][] }) {
  return (
    <Button
      variant="text"
      icon="download"
      testId={`csv-${filename}`}
      onClick={() => {
        const url = URL.createObjectURL(new Blob([toCsv(header, rows)], { type: "text/csv;charset=utf-8" }));
        const a = document.createElement("a");
        a.href = url;
        a.download = `${filename}.csv`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }}
    >
      {msg}
    </Button>
  );
}

export function PrintButton({ msg }: { msg: string }) {
  return (
    <Button variant="text" icon="print" testId="print-page" onClick={() => window.print()}>
      {msg}
    </Button>
  );
}

/** Re-reads the server data of the page in place (no navigation, filter and scroll kept). */
export function RetryButton({ msg }: { msg: string }) {
  const router = useRouter();
  const [pending, start] = useTransition();
  return (
    <Button variant="secondary" icon="refresh" testId="retry" aria-busy={pending} onClick={() => start(() => router.refresh())}>
      {msg}
    </Button>
  );
}

/** The tick rule, pure so it is tested without a DOM: refresh only when the tab is visible and at least `REFRESH_MS` passed since the last read. */
export function shouldRefresh(now: number, lastRead: number, visible: boolean, everyMs: number = REFRESH_MS): boolean {
  return visible && now - lastRead >= Math.max(everyMs, REFRESH_MS);
}

/** A focus event only counts when the last read is older than this, so a focus storm (alt-tab, devtools) cannot hammer the API. */
export const FOCUS_MIN_MS = 5_000;
/** A tick that fires a little early or late must not skip a whole 30 s round. */
const TICK_SLACK_MS = 1_000;

function stamp(locale: string, at: number): string {
  return new Date(at).toLocaleTimeString(locale === "bn" ? "bn-BD" : "en-GB", { timeZone: "Asia/Dhaka", hour12: false });
}

/**
 * Refreshes the server-rendered data every 30 s while the tab is visible (D-NIGHT-SYNC-01, web part), on window focus and when the tab
 * becomes visible again; no timer work in a hidden tab. With `label` ("... {time}") it also shows when the page was last read.
 */
export function AutoRefresh({ everyMs = REFRESH_MS, label, locale = "en" }: { everyMs?: number; label?: string; locale?: string }) {
  const router = useRouter();
  const last = useRef(0);
  const [at, setAt] = useState<number | null>(null);
  useEffect(() => {
    last.current = Date.now();
    setAt(last.current);
    const read = () => {
      last.current = Date.now();
      setAt(last.current);
      router.refresh();
    };
    const tick = () => {
      if (shouldRefresh(Date.now() + TICK_SLACK_MS, last.current, document.visibilityState === "visible", everyMs)) read();
    };
    const onFocus = () => {
      if (document.visibilityState === "visible" && Date.now() - last.current >= FOCUS_MIN_MS) read();
    };
    const id = setInterval(tick, Math.max(everyMs, REFRESH_MS));
    document.addEventListener("visibilitychange", tick);
    window.addEventListener("focus", onFocus);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", tick);
      window.removeEventListener("focus", onFocus);
    };
  }, [router, everyMs]);
  if (!label || at === null) return null;
  return <span data-testid="dash-refreshed" suppressHydrationWarning> · {label.replace("{time}", stamp(locale, at))}</span>;
}
