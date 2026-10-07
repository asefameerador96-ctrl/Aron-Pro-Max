// F-ADM-051 Config page P14: the same list as the quarantine review.
import { QuarantinePageContent } from "@/components/admin/config/quarantine-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function ConfigQuarantinePage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <QuarantinePageContent searchParams={searchParams} basePath="/admin/config/quarantine" titleKey="cfgp14.title" />;
}
