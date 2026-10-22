"use client";
// The multi-select of the filter panel: a button reading "All Selected (n)" (or "n Selected") with a clear ×, a chevron, and a listbox of
// checkboxes with a search box. Nothing selected means "everything in my scope", exactly as on the original portal.
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Icon } from "@/components/kit/icons";

export interface Opt {
  id: string;
  name: string;
}

export function MultiSelect({ label, options, value, onChange, allText, someText, clearLabel, searchLabel, noneText, testId }: { label: string; options: Opt[]; value: string[]; onChange: (v: string[]) => void; allText: string; someText: (n: number) => string; clearLabel: string; searchLabel: string; noneText: string; testId: string }) {
  const [open, setOpen] = useState(false);
  const [needle, setNeedle] = useState("");
  const root = useRef<HTMLDivElement>(null);
  const btn = useRef<HTMLButtonElement>(null);
  const lid = useId();
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);
  const shown = useMemo(() => options.filter((o) => o.name.toLowerCase().includes(needle.trim().toLowerCase())), [options, needle]);
  const chosen = value.filter((v) => options.some((o) => o.id === v));
  const text = chosen.length === 0 || chosen.length === options.length ? allText : someText(chosen.length);
  const toggle = (id: string) => onChange(chosen.includes(id) ? chosen.filter((v) => v !== id) : [...chosen, id]);
  return (
    <div ref={root} className="relative" data-testid={testId} onKeyDown={(e) => { if (e.key === "Escape" && open) { setOpen(false); btn.current?.focus(); } }}>
      <span className="mb-1 block text-label font-semibold text-accent" id={`${lid}-l`}>{label}</span>
      <div className="flex min-h-(--ctl-h) items-center rounded-chip border border-input bg-raised text-body text-primary">
        <button ref={btn} type="button" aria-haspopup="listbox" aria-expanded={open} aria-labelledby={`${lid}-l ${lid}-t`} onClick={() => setOpen((o) => !o)} className="flex min-h-(--ctl-h) min-w-0 flex-1 items-center px-3 text-start focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
          <span id={`${lid}-t`} className="truncate" data-testid={`${testId}-text`}>{text}</span>
        </button>
        {chosen.length > 0 && chosen.length < options.length ? (
          <button type="button" aria-label={`${clearLabel}: ${label}`} onClick={() => onChange([])} data-testid={`${testId}-clear`} className="flex size-(--ctl-h) shrink-0 items-center justify-center rounded-full text-secondary hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
            <Icon name="close" size={16} />
          </button>
        ) : null}
        <Icon name="chevron-down" size={18} className="me-2 shrink-0 text-secondary" />
      </div>
      {open ? (
        <div className="glass-pop absolute start-0 top-full z-40 mt-1 w-full min-w-48 p-2">
          {options.length > 8 ? <input type="search" value={needle} onChange={(e) => setNeedle(e.target.value)} aria-label={searchLabel} placeholder={searchLabel} className="mb-2 min-h-(--ctl-h) w-full rounded-chip border border-input bg-raised px-3 text-body text-primary focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" /> : null}
          <ul role="listbox" aria-multiselectable="true" aria-labelledby={`${lid}-l`} className="max-h-64 overflow-auto">
            {shown.length === 0 ? <li className="px-2 py-2 text-caption text-secondary">{noneText}</li> : null}
            {shown.map((o) => (
              <li key={o.id} role="option" aria-selected={chosen.includes(o.id)}>
                <label className="flex min-h-(--ctl-h) cursor-pointer items-center gap-2 rounded-chip px-2 hover:bg-pressed">
                  <input type="checkbox" checked={chosen.includes(o.id)} onChange={() => toggle(o.id)} className="size-4 accent-(--accent)" />
                  <span className="min-w-0 flex-1 truncate" title={o.name}>{o.name}</span>
                </label>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
