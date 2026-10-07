import { CodeListsPageContent } from "@/components/admin/config/code-lists-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function QcFaultsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <CodeListsPageContent searchParams={searchParams} basePath="/admin/qc-faults" fixed="qc_fault_type" />;
}
