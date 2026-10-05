import type { NextResponse} from "next/server";
import { type NextRequest } from "next/server";
import { handleUpdate } from "@/components/admin/crud/server";
import { problemResponse } from "@/lib/api/guard";
import { entityBySlug } from "@/app/admin/_entities/registry";

export async function PATCH(req: NextRequest, ctx: { params: Promise<{ entity: string; id: string }> }): Promise<NextResponse> {
  const { entity, id } = await ctx.params;
  const meta = entityBySlug(entity);
  if (!meta) return problemResponse(404, "ERR_NOT_FOUND");
  return handleUpdate(req, meta, id);
}
