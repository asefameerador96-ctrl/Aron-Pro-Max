// F-ADM-013: the operating-parameter console.
import { ConfigConsolePage } from "@/components/admin/config/config-console-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function KeysPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <ConfigConsolePage searchParams={searchParams} basePath="/admin/config/keys" titleKey="cfgk.title" introKey="cfgk.intro" />;
}
