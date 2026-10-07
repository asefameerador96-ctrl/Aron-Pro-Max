// F-ADM-040 Config page P3: rules and thresholds (settings and time-shaped keys).
import { ConfigConsolePage } from "@/components/admin/config/config-console-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function RulesPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <ConfigConsolePage searchParams={searchParams} basePath="/admin/config/rules" titleKey="cfgk.rules.title" introKey="cfgk.rules.intro" kinds={["S", "T"]} />;
}
