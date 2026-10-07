import { notFound } from "next/navigation";
import { EntityActionPage } from "@/components/admin/crud/pages";
import { entityBySlug } from "../../../_entities/registry";

export default async function Page({ params, searchParams }: { params: Promise<{ entity: string; id: string; action: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { entity, id, action } = await params;
  const meta = entityBySlug(entity);
  if (!meta || !/^[0-9]{1,15}$/.test(id)) notFound();
  return <EntityActionPage meta={meta} id={id} actionKey={action} searchParams={await searchParams} />;
}
