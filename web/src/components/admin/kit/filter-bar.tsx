import Link from "next/link";
import type { ReactNode } from "react";

export interface FilterControl {
  param: string;
  label: string;
  kind: "int" | "enum" | "search" | "date";
  value: string;
  options?: { value: string; label: string }[];
}

/** GET form: filters live in the URL, so lists are linkable and survive reloads. */
export function FilterBar({ controls, applyLabel, clearLabel, allLabel, clearHref, children }: { controls: readonly FilterControl[]; applyLabel: string; clearLabel: string; allLabel: string; clearHref: string; children?: ReactNode }) {
  if (controls.length === 0) return null;
  const cls = "rounded border border-slate-300 bg-white px-2 py-1.5 text-sm";
  return (
    <form method="get" className="flex flex-wrap items-end gap-3 rounded-lg border border-slate-200 bg-white p-3" data-testid="filter-bar">
      {controls.map((c) => (
        <label key={c.param} className="text-sm text-slate-700">
          <span className="mb-1 block">{c.label}</span>
          {c.kind === "enum" ? (
            <select name={c.param} defaultValue={c.value} className={cls}>
              <option value="">{allLabel}</option>
              {c.options?.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          ) : (
            <input name={c.param} type={c.kind === "date" ? "date" : "text"} defaultValue={c.value} inputMode={c.kind === "int" ? "numeric" : undefined} className={`${cls} w-40`} />
          )}
        </label>
      ))}
      <button type="submit" className="rounded bg-brand-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-brand-700">
        {applyLabel}
      </button>
      <Link href={clearHref} className="px-2 py-1.5 text-sm text-slate-600 underline">
        {clearLabel}
      </Link>
      {children}
    </form>
  );
}
