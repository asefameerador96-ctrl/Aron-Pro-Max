// F-WEB-051: Web Final Submit (TSO and up). The same server rule as the app: online, once per zone and day.
// (The status page with the zone badges, F-WEB-047, lives at /final-submit.)
import { FinalSubmitPageContent } from "@/components/admin/config/final-submit-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function FinalSubmitEntryPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <FinalSubmitPageContent searchParams={searchParams} />;
}
