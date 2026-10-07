// F-WEB-050: Web Entry (route-day aggregate). TSO for own zones, administrators.
import { WebEntryPageContent } from "@/components/admin/config/web-entry-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function WebEntryPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <WebEntryPageContent searchParams={searchParams} />;
}
