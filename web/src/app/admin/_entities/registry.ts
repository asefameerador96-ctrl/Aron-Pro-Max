// Every entity the portal manages. Adding a table = one file in this folder + one line here.
import type { AnyEntity } from "@/components/admin/crud/meta";
import { clusters } from "./clusters";
import { geoEntities } from "./geo";

export const ENTITIES: readonly AnyEntity[] = [...geoEntities, clusters];

export function entityBySlug(slug: string): AnyEntity | undefined {
  return ENTITIES.find((e) => e.slug === slug);
}
