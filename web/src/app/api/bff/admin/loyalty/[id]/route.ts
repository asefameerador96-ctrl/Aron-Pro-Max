import type { NextRequest, NextResponse } from "next/server";
import { handleProgrammeUpdate } from "@/lib/admin/loyalty-server";

export async function PATCH(req: NextRequest, ctx: { params: Promise<{ id: string }> }): Promise<NextResponse> {
  return handleProgrammeUpdate(req, (await ctx.params).id);
}
