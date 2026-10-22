import type { ReactNode } from "react";
import { Forbidden } from "@/components/forbidden";
import { loadMenuAccess } from "@/lib/menu/matrix";
import { ModuleReadOnlyNote } from "@/components/admin/module-read-only-note";
import { Shell } from "@/components/shell";
import { guard } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

// Role-gated route group: ADMIN_PORTAL_ROLES only (the proxy answers 403 first; this repeats the check).
export default async function AdminLayout({ children }: { children: ReactNode }) {
  const [{ allowed, session }, locale] = await Promise.all([guard(), getLocale()]);
  if (!allowed) return <Forbidden locale={locale} />;
  const { allowed: allowedMenus, grants } = await loadMenuAccess(session.at, session.user.role);
  return (
    <Shell session={session} locale={locale} allowedMenus={allowedMenus} grants={grants}>
      <ModuleReadOnlyNote grants={grants} />
      {children}
    </Shell>
  );
}
