import { AuditPageContent } from "@/components/admin/config/audit-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function AuditPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <AuditPageContent searchParams={searchParams} basePath="/admin/audit" />;
}
