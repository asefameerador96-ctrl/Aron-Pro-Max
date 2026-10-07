// F-ADM-029: reopen a final-submitted zone-day; late syncs after the final.
import { DayControlPageContent } from "@/components/admin/config/day-control-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function DayPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <DayControlPageContent searchParams={searchParams} basePath="/admin/day" withMissing={false} />;
}
