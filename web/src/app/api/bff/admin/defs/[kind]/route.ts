import type { NextRequest, NextResponse } from "next/server";
import { handleDefCreate } from "@/components/admin/tutorials-server";

export async function POST(req: NextRequest, ctx: { params: Promise<{ kind: string }> }): Promise<NextResponse> {
  return handleDefCreate(req, (await ctx.params).kind);
}
