"use client";
import { useState } from "react";
import { useI18n } from "./i18n-provider";
import { Icon } from "./kit/icons";

export function LogoutButton() {
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
      className="inline-flex min-h-(--ctl-h) items-center gap-2 rounded-full border border-divider bg-raised px-3 text-label text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus aria-disabled:bg-disabled-fill aria-disabled:text-disabled-label"
    >
      <Icon name="logout" size={18} />
      <span className="hidden sm:inline">{t("auth.logout")}</span>
      <span className="sr-only sm:hidden">{t("auth.logout")}</span>
    </button>
  );
}
