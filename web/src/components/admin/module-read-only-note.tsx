"use client";
// An ADMIN with a read grant sees the module's pages without actions (D-WEB-ADMIN-MENU-01); this line says why.
import { usePathname } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { accessOf, moduleOfPage, type ModuleGrants } from "@/lib/admin/modules";

export function ModuleReadOnlyNote({ grants }: { grants: ModuleGrants | null }) {
  const { t } = useI18n();
  const pathname = usePathname();
  if (accessOf(grants, moduleOfPage(pathname)) !== "r") return null;
  return <p role="note" data-testid="module-read-only" className="mb-3 rounded border border-amber-300 bg-amber-50 p-2 text-sm text-amber-900">{t("module.read_only")}</p>;
}
