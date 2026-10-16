"use client";
// Command search placeholder (web-glass s6): Ctrl/Cmd+K focuses it. It searches nothing yet and says so; it never shows
// results it does not have (the page, config-key and outlet index is a later request).
import { useEffect, useRef, useState } from "react";
import { useI18n } from "./i18n-provider";
import { Icon } from "./kit/icons";

export function SearchField() {
  const { t } = useI18n();
  const ref = useRef<HTMLInputElement>(null);
  const [note, setNote] = useState(false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        ref.current?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);
  return (
    <form
      role="search"
      className="relative hidden min-w-0 flex-1 sm:block sm:max-w-sm"
      onSubmit={(e) => {
        e.preventDefault();
        setNote(true);
      }}
    >
      <Icon name="search" size={18} className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-secondary" />
      <input ref={ref} type="search" name="command" placeholder={t("search.placeholder")} aria-label={t("search.label")} aria-describedby="search-note" autoComplete="off" className="h-10 w-full rounded-full border border-input bg-raised ps-10 pe-16 text-body text-primary placeholder:text-placeholder focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus" onFocus={() => setNote(false)} />
      {/* A key cap, not prose: keyboard keys are never translated. */}
      {/* eslint-disable-next-line local/no-hardcoded-text */}
      <kbd className="pointer-events-none absolute end-3 top-1/2 hidden -translate-y-1/2 rounded-md border border-divider bg-raised px-1.5 text-[13px] leading-5 text-secondary md:inline" aria-hidden="true">
        Ctrl K
      </kbd>
      <p id="search-note" role="status" className={`absolute start-0 top-11 z-40 ${note ? "glass-pop px-3 py-2" : "sr-only"} text-caption text-secondary`}>
        {t("search.soon")}
      </p>
    </form>
  );
}
