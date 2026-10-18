"use client";
// Client controls shared by the dashboard tables and tiles: CSV download of the rows on screen, print, retry, and the
// auto-refresh that never runs faster than 60 s and stays quiet while the tab is hidden.
import { useRouter } from "next/navigation";
import { useEffect, useRef, useTransition } from "react";
import { Button } from "@/components/kit/button";
import { toCsv } from "@/lib/dash/csv";

export const REFRESH_MS = 60_000;

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

/** Refreshes the server-rendered data no faster than every 60 s; paused while the tab is hidden, and one refresh as soon as it is visible again when the data is older than that. */
export function AutoRefresh({ everyMs = REFRESH_MS }: { everyMs?: number }) {
  const router = useRouter();
  const last = useRef(0);
  useEffect(() => {
    last.current = Date.now();
    const tick = () => {
      if (shouldRefresh(Date.now(), last.current, document.visibilityState === "visible", everyMs)) {
        last.current = Date.now();
        router.refresh();
      }
    };
    const id = setInterval(tick, Math.max(everyMs, REFRESH_MS));
    document.addEventListener("visibilitychange", tick);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", tick);
    };
  }, [router, everyMs]);
  return null;
}
