import { notFound } from "next/navigation";
import { EntityListPage } from "@/components/admin/crud/pages";
import { entityBySlug } from "../_entities/registry";

export default async function Page({ params, searchParams }: { params: Promise<{ entity: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const meta = entityBySlug((await params).entity);
  if (!meta) notFound();
  return <EntityListPage meta={meta} searchParams={await searchParams} />;
}
