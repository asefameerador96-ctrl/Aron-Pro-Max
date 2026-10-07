// Every entity the portal manages. Adding a table = one file in this folder + one line here.
import type { AnyEntity } from "@/components/admin/crud/meta";
import { holidays } from "./calendar";
import { productNodeEntities, skus } from "./products";
import { clusters } from "./clusters";
import { geoEntities } from "./geo";
import { routeAssignments, routes } from "./routes";
import { users } from "./users";

export const ENTITIES: readonly AnyEntity[] = [...geoEntities, clusters, routes, routeAssignments, users, ...productNodeEntities, skus, holidays];

export function entityBySlug(slug: string): AnyEntity | undefined {
  return ENTITIES.find((e) => e.slug === slug);
}
