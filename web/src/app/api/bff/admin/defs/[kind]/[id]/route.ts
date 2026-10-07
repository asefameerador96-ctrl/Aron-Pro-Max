import type { NextRequest, NextResponse } from "next/server";
import { handleDefUpdate } from "@/components/admin/tutorials-server";

export async function PATCH(req: NextRequest, ctx: { params: Promise<{ kind: string; id: string }> }): Promise<NextResponse> {
  const { kind, id } = await ctx.params;
  return handleDefUpdate(req, kind, id);
}
