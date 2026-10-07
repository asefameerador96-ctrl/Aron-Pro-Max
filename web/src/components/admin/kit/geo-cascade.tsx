"use client";
// Wing → division → territory → house → zone selectors in a GET form: choosing a level clears the deeper ones and reloads
// the page, so the server renders the options of the next level. Same shape as the manual's panel (select, then View).
import { useRef } from "react";

export interface CascadeLevel {
  param: string;
  label: string;
  value: string;
  options: { value: string; label: string }[];
}

export interface CascadeExtra {
  name: string;
  value: string;
  label: string;
  type?: "text" | "date";
}

export function GeoCascade({ levels, viewLabel, allLabel, action, extra }: { levels: CascadeLevel[]; viewLabel: string; allLabel: string; action: string; extra?: CascadeExtra[] }) {
  const form = useRef<HTMLFormElement>(null);
  function changed(index: number) {
    const f = form.current;
    if (!f) return;
    for (const deeper of levels.slice(index + 1)) {
      const el = f.elements.namedItem(deeper.param);
      if (el instanceof HTMLSelectElement) el.value = "";
    }
    f.requestSubmit();
  }
  return (
    <form ref={form} method="get" action={action} className="flex flex-wrap items-end gap-3 rounded-lg border border-slate-200 bg-white p-3" data-testid="geo-cascade">
      {levels.map((l, i) => (
        <label key={l.param} className="text-sm text-slate-700">
          <span className="mb-1 block">{l.label}</span>
          <select name={l.param} defaultValue={l.value} onChange={() => changed(i)} className="rounded border border-slate-300 bg-white px-2 py-1.5 text-sm">
            <option value="">{allLabel}</option>
            {l.options.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
      ))}
      {extra?.map((x) => (
        <label key={x.name} className="text-sm text-slate-700">
          <span className="mb-1 block">{x.label}</span>
          <input name={x.name} type={x.type ?? "text"} defaultValue={x.value} className="rounded border border-slate-300 bg-white px-2 py-1.5 text-sm" />
        </label>
      ))}
      <button type="submit" className="rounded-full bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700">
        {viewLabel}
      </button>
    </form>
  );
}
