"use client";
import { useState } from "react";
import { useI18n } from "./i18n-provider";

export function LogoutButton() {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  return (
    <button
      type="button"
      disabled={busy}
      onClick={async () => {
        setBusy(true);
        await fetch("/api/bff/logout", { method: "POST" }).catch(() => undefined);
        window.location.replace("/login");
      }}
      className="rounded border border-slate-300 px-3 py-1 text-sm text-slate-700 hover:bg-slate-100 disabled:opacity-50"
    >
      {t("auth.logout")}
    </button>
  );
}
