import { notFound } from "next/navigation";
import { entityCanEdit, validId } from "@/components/admin/crud/meta";
import { EntityEditPage } from "@/components/admin/crud/pages";
import { entityBySlug } from "../../_entities/registry";

export default async function Page({ params, searchParams }: { params: Promise<{ entity: string; id: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { entity, id } = await params;
  const meta = entityBySlug(entity);
  if (!meta || !validId(meta, id) || !entityCanEdit(meta)) notFound();
  return <EntityEditPage meta={meta} id={id} searchParams={await searchParams} />;
}
