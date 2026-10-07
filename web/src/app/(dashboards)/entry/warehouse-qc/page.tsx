// F-WEB-060: Warehouse QC Entry: zone, one date, the same grid, a reason on every save.
import { QcEntryPageContent } from "@/components/admin/config/qc-entry-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function WarehouseQcPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <QcEntryPageContent searchParams={searchParams} source="warehouse" />;
}
