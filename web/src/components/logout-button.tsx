"use client";
import { useState } from "react";
import { useI18n } from "./i18n-provider";
import { Icon } from "./kit/icons";

/** `bar` is the pill in the top bar (phones); `rail` is the red full-width Logout at the foot of the sidebar (PARITY-SPEC s4). */
export function LogoutButton({ variant = "bar" }: { variant?: "bar" | "rail" }) {
  const rail = variant === "rail";
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  return (
    <button
      type="button"
      aria-disabled={busy || undefined}
      aria-busy={busy || undefined}
      onClick={async () => {
        if (busy) return;
        setBusy(true);
        await fetch("/api/bff/logout", { method: "POST" }).catch(() => undefined);
        window.location.replace("/login");
      }}
      className={
        rail
          ? "rail-logout flex min-h-(--ctl-h) w-full items-center justify-center gap-2 rounded-chip bg-rail-danger px-3 text-body-strong !text-on-rail hover:bg-rail-danger-hover focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus aria-disabled:opacity-70"
          : "inline-flex min-h-(--ctl-h) items-center gap-2 rounded-full border border-divider bg-raised px-3 text-label text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus aria-disabled:bg-disabled-fill aria-disabled:text-disabled-label"
      }
    >
      <Icon name="logout" size={18} />
      {rail ? (
        <span className="rail-label">{t("auth.logout")}</span>
      ) : (
        <>
          <span className="hidden sm:inline">{t("auth.logout")}</span>
          <span className="sr-only sm:hidden">{t("auth.logout")}</span>
        </>
      )}
    </button>
  );
}
