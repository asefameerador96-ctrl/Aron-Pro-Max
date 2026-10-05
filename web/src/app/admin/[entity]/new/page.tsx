import { notFound } from "next/navigation";
import { EntityCreatePage } from "@/components/admin/crud/pages";
import { entityBySlug } from "../../_entities/registry";

export default async function Page({ params }: { params: Promise<{ entity: string }> }) {
  const meta = entityBySlug((await params).entity);
  if (!meta) notFound();
  return <EntityCreatePage meta={meta} />;
}
