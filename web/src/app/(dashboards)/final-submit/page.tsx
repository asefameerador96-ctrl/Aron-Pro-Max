// F-WEB-051: Web Final Submit (TSO and up). The same server rule as the app: online, once per zone and day.
import { FinalSubmitPageContent } from "@/components/admin/config/final-submit-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function FinalSubmitPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <FinalSubmitPageContent searchParams={searchParams} />;
}
