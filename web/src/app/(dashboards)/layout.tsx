import type { ReactNode } from "react";
import { Forbidden } from "@/components/forbidden";
import { loadAllowedMenus } from "@/lib/menu/matrix";
import { Shell } from "@/components/shell";
import { guard } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function DashboardsLayout({ children }: { children: ReactNode }) {
  const [{ allowed, session }, locale] = await Promise.all([guard(), getLocale()]);
  if (!allowed) return <Forbidden locale={locale} />;
  const allowedMenus = await loadAllowedMenus(session.at, session.user.role);
  return (
    <Shell session={session} locale={locale} allowedMenus={allowedMenus}>
      {children}
    </Shell>
  );
}
