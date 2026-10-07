// Every entity the portal manages. Adding a table = one file in this folder + one line here.
import type { AnyEntity } from "@/components/admin/crud/meta";
import { productNodeEntities, skus } from "./products";
import { feedback } from "./feedback";
import { outletRequests, outlets } from "./outlets";
import { clusters } from "./clusters";
import { geoEntities } from "./geo";
import { routeAssignments, routes } from "./routes";
import { users } from "./users";

export const ENTITIES: readonly AnyEntity[] = [...geoEntities, clusters, routes, routeAssignments, users, ...productNodeEntities, skus, outlets, outletRequests, feedback];

export function entityBySlug(slug: string): AnyEntity | undefined {
  return ENTITIES.find((e) => e.slug === slug);
}
