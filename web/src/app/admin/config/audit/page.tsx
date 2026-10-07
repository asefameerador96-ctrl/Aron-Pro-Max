// Config page P16: the audit viewer inside the configuration console (same content as /admin/audit).
import { AuditPageContent } from "@/components/admin/config/audit-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function ConfigAuditPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <AuditPageContent searchParams={searchParams} basePath="/admin/config/audit" titleKey="cfgp.audit.title" />;
}
