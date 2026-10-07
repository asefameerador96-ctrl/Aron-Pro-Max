import { CodeListsPageContent } from "@/components/admin/config/code-lists-page";
import type { SearchParams } from "@/components/admin/kit/page";

export default function CodeListsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  return <CodeListsPageContent searchParams={searchParams} basePath="/admin/code-lists" />;
}
