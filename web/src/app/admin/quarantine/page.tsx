import { QuarantinePageContent } from "@/components/admin/config/quarantine-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function QuarantinePage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <QuarantinePageContent searchParams={searchParams} basePath="/admin/quarantine" />;
}
