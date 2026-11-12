import type { NextRequest, NextResponse } from "next/server";
import { handleProgrammeStatus } from "@/lib/admin/loyalty-server";

export async function POST(req: NextRequest, ctx: { params: Promise<{ id: string }> }): Promise<NextResponse> {
  return handleProgrammeStatus(req, (await ctx.params).id);
}
