// F-ADM-052 Config page P15: day control (reopen, late-sync queue, missing check-outs).
import { DayControlPageContent } from "@/components/admin/config/day-control-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function DayControlPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <DayControlPageContent searchParams={searchParams} basePath="/admin/config/day" withMissing />;
}
