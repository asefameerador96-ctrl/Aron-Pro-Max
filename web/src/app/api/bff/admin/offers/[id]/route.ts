import type { NextRequest, NextResponse } from "next/server";
import { handleOfferUpdate } from "@/lib/admin/offers-server";

export async function PATCH(req: NextRequest, ctx: { params: Promise<{ id: string }> }): Promise<NextResponse> {
  return handleOfferUpdate(req, (await ctx.params).id);
}
