import type { Me } from "@/contract/types";
import { t, type Locale } from "@/lib/i18n";
import { PiiReveal } from "./pii-reveal";

/** The reveal control for a role whose /v1/me lists "pii.reveal" (ADMIN, SUPERADMIN today); nothing for anyone else. */
export function PiiRevealBar({ me, locale }: { me: Me | null; locale: Locale }) {
  if (!me?.permissions?.includes("pii.reveal")) return null;
  return (
    <PiiReveal
      piiUntil={me.pii_until ?? null}
      needsCode={me.mfa_enabled === true}
      labels={{ open: t(locale, "pii.open"), password: t(locale, "pii.password"), code: t(locale, "pii.code"), submit: t(locale, "pii.submit"), cancel: t(locale, "common.cancel"), active: t(locale, "pii.active"), bad: t(locale, "pii.bad"), locked: t(locale, "pii.locked"), resignin: t(locale, "pii.resignin"), limited: t(locale, "pii.limited"), failed: t(locale, "pii.failed") }}
    />
  );
}
