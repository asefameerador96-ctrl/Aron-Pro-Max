import type { NextRequest, NextResponse } from "next/server";
import { handleAdjustPost, handleAdjustPut } from "@/lib/admin/loyalty-server";

export async function POST(req: NextRequest, ctx: { params: Promise<{ id: string }> }): Promise<NextResponse> {
  return handleAdjustPost(req, (await ctx.params).id);
}

export async function PUT(req: NextRequest, ctx: { params: Promise<{ id: string }> }): Promise<NextResponse> {
  return handleAdjustPut(req, (await ctx.params).id);
}
