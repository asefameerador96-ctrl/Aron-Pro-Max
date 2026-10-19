"use client";
// The orange Filter button of the portal home: a popover form (GET) with the business date and the cascading scope selects.
// Choosing a level submits at once so the next level loads its options; deeper selects are cleared first, so a stale child of
// another parent is never sent. The popover stays open while a scope is applied.
import { useEffect, useRef, type ReactNode } from "react";
import { Icon } from "@/components/kit/icons";

export function HomeFilter({ msg, applyMsg, resetMsg, resetHref, active, children }: { msg: string; applyMsg: string; resetMsg: string; resetHref: string; active: boolean; children: ReactNode }) {
  const form = useRef<HTMLFormElement>(null);
  const box = useRef<HTMLDetailsElement>(null);
  // `data-ready` marks that the handlers are attached; tests (and nothing else) wait for it before pressing keys.
  useEffect(() => box.current?.setAttribute("data-ready", ""), []);
  // Escape closes the popover and returns focus to its button (keyboard users never get trapped in it).
  const onKey = (e: React.KeyboardEvent<HTMLDetailsElement>) => {
    if (e.key !== "Escape" || !box.current?.open) return;
    box.current.open = false;
    box.current.querySelector("summary")?.focus();
  };
  const onChange = (e: React.FormEvent<HTMLFormElement>) => {
    const el = e.target as unknown as HTMLSelectElement;
    if (el.tagName !== "SELECT") return;
    const selects = Array.from(e.currentTarget.querySelectorAll("select"));
    for (const s of selects.slice(selects.indexOf(el) + 1)) s.value = "";
    form.current?.requestSubmit();
  };
  return (
    <details ref={box} onKeyDown={onKey} className="relative" open={active} data-testid="home-filter">
      <summary className="inline-flex min-h-(--ctl-h) cursor-pointer list-none items-center gap-2 rounded-full bg-filter-fill px-4 text-body-strong text-on-accent hover:brightness-110 focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus [&::-webkit-details-marker]:hidden">
        <Icon name="filter" size={18} />
        {msg}
        {active ? <span className="size-2 rounded-full bg-on-rail" aria-hidden="true" /> : null}
      </summary>
      <form ref={form} method="get" onChange={onChange} className="glass-pop absolute end-0 top-12 z-40 flex w-[min(92vw,44rem)] flex-col gap-3 p-4">
        {children}
        <div className="flex flex-wrap gap-2">
          <button type="submit" className="inline-flex min-h-(--ctl-h) items-center justify-center gap-2 rounded-full border border-divider bg-raised px-5 text-body-strong text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
            <Icon name="filter" size={18} />
            {applyMsg}
          </button>
          <a href={resetHref} className="inline-flex min-h-(--ctl-h) items-center rounded-full px-4 text-body-strong text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" data-testid="home-filter-reset">
            {resetMsg}
          </a>
        </div>
      </form>
    </details>
  );
}
