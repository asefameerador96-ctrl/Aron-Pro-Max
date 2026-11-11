import type { NextRequest, NextResponse } from "next/server";
import { handleOfferStatus } from "@/lib/admin/offers-server";

export async function POST(req: NextRequest, ctx: { params: Promise<{ id: string }> }): Promise<NextResponse> {
  return handleOfferStatus(req, (await ctx.params).id);
}
