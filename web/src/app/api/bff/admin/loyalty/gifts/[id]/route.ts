import type { NextRequest, NextResponse } from "next/server";
import { handleGiftUpdate } from "@/lib/admin/loyalty-server";

export async function PATCH(req: NextRequest, ctx: { params: Promise<{ id: string }> }): Promise<NextResponse> {
  return handleGiftUpdate(req, (await ctx.params).id);
}
