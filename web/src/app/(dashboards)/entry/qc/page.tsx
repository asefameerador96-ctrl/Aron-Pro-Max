// F-WEB-052: QC Entry (Market QC): zone, route, date and the SKU by fault grid.
import { QcEntryPageContent } from "@/components/admin/config/qc-entry-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function QcEntryPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <QcEntryPageContent searchParams={searchParams} source="market" />;
}
