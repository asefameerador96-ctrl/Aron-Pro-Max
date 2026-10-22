"use client";
// The round blue "i" at the foot of a sales card: opens a small dialog with Daily Target, Sales so far and Remaining (brief s2). The numbers
// arrive as text from the page (one formatter for the card and the pop-up). Escape and the close button return focus to the "i".
import { useEffect, useId, useRef, useState } from "react";
import { Icon } from "@/components/kit/icons";

export interface InfoRow {
  label: string;
  value: string;
}

export function InfoPopup({ openLabel, title, rows, emptyText, closeLabel, testId }: { openLabel: string; title: string; rows: InfoRow[] | null; emptyText: string; closeLabel: string; testId: string }) {
  const [open, setOpen] = useState(false);
  const btn = useRef<HTMLButtonElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setOpen(false);
      btn.current?.focus();
    };
    const onDown = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node) && !btn.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onDown);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("mousedown", onDown);
    };
  }, [open]);
  return (
    <div className="no-print absolute bottom-2 end-3">
      <button ref={btn} type="button" aria-label={openLabel} aria-expanded={open} aria-controls={id} onClick={() => setOpen((o) => !o)} data-testid={testId} className="flex size-9 items-center justify-center rounded-full bg-accent text-on-accent hover:brightness-110 focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
        <Icon name="info" size={20} />
      </button>
      {open ? (
        <div ref={box} id={id} role="dialog" aria-label={title} data-testid={`${testId}-dialog`} className="glass-pop absolute bottom-11 end-0 z-30 w-64 p-3 text-body text-primary">
          <div className="mb-2 flex items-center justify-between gap-2">
            <h3 className="text-body-strong text-accent">{title}</h3>
            <button type="button" aria-label={closeLabel} onClick={() => { setOpen(false); btn.current?.focus(); }} className="flex size-8 items-center justify-center rounded-full text-secondary hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
              <Icon name="close" size={16} />
            </button>
          </div>
          {rows ? (
            <dl className="space-y-1">
              {rows.map((r) => (
                <div key={r.label} className="flex items-baseline justify-between gap-3">
                  <dt className="text-secondary">{r.label}</dt>
                  <dd className="text-body-strong tabular-nums" data-testid={`${testId}-${r.label}`}>{r.value}</dd>
                </div>
              ))}
            </dl>
          ) : (
            <p className="text-caption text-secondary">{emptyText}</p>
          )}
        </div>
      ) : null}
    </div>
  );
}
