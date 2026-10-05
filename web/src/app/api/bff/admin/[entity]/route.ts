import type { NextResponse} from "next/server";
import { type NextRequest } from "next/server";
import { handleCreate } from "@/components/admin/crud/server";
import { problemResponse } from "@/lib/api/guard";
import { entityBySlug } from "@/app/admin/_entities/registry";

export async function POST(req: NextRequest, ctx: { params: Promise<{ entity: string }> }): Promise<NextResponse> {
  const meta = entityBySlug((await ctx.params).entity);
  if (!meta) return problemResponse(404, "ERR_NOT_FOUND");
  return handleCreate(req, meta);
}
