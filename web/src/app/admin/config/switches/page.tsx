// F-ADM-041 Config page P4: operational switches (kind O), each with a mandatory duration.
import { ConfigConsolePage } from "@/components/admin/config/config-console-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function SwitchesPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <ConfigConsolePage searchParams={searchParams} basePath="/admin/config/switches" titleKey="cfgk.switches.title" introKey="cfgk.switches.intro" kinds={["O"]} switches />;
}
