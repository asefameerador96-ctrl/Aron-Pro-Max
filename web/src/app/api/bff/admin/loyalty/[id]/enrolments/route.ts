import type { NextRequest, NextResponse } from "next/server";
import { handleEnrolments } from "@/lib/admin/loyalty-server";

export async function PUT(req: NextRequest, ctx: { params: Promise<{ id: string }> }): Promise<NextResponse> {
  return handleEnrolments(req, (await ctx.params).id);
}
