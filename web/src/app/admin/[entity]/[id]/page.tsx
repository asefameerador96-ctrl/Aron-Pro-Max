import { notFound } from "next/navigation";
import { EntityEditPage } from "@/components/admin/crud/pages";
import { entityBySlug } from "../../_entities/registry";

export default async function Page({ params, searchParams }: { params: Promise<{ entity: string; id: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { entity, id } = await params;
  const meta = entityBySlug(entity);
  if (!meta || !/^[0-9]{1,15}$/.test(id)) notFound();
  return <EntityEditPage meta={meta} id={id} searchParams={await searchParams} />;
}
