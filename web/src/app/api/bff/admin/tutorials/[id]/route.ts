import type { NextRequest, NextResponse } from "next/server";
import { handleTutorialUpdate } from "@/components/admin/tutorials-server";

export async function PATCH(req: NextRequest, ctx: { params: Promise<{ id: string }> }): Promise<NextResponse> {
  return handleTutorialUpdate(req, (await ctx.params).id);
}
