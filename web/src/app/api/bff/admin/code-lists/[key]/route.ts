import type { NextRequest, NextResponse } from "next/server";
import { handleCodeListPut } from "@/components/admin/crud/codelists-server";

export async function PUT(req: NextRequest, ctx: { params: Promise<{ key: string }> }): Promise<NextResponse> {
  return handleCodeListPut(req, (await ctx.params).key);
}
