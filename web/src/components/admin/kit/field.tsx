import type { ReactNode } from "react";

/** Label + control + hint + error wrapper shared by every admin form. */
export function Field({ label, htmlFor, required, hint, error, children }: { label: string; htmlFor: string; required?: boolean; hint?: string; error?: string | null; children: ReactNode }) {
  return (
    <div className="space-y-1">
      <label htmlFor={htmlFor} className="block text-sm font-medium text-slate-800">
        {label}
        {required ? <span aria-hidden="true" className="ml-1 text-red-600">*</span> : null}
      </label>
      {children}
      {hint ? <p className="text-xs text-slate-500">{hint}</p> : null}
      {error ? (
        <p role="alert" data-testid={`error-${htmlFor}`} className="text-xs font-medium text-red-700">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export const inputClass =
  "w-full min-h-11 rounded-[var(--radius-chip)] border border-slate-300 bg-white px-3.5 py-2.5 text-sm transition-[border-color,box-shadow] duration-[var(--motion-fast)] focus:border-brand-600 focus:outline-none focus:ring-1 focus:ring-brand-600 disabled:bg-slate-100";
