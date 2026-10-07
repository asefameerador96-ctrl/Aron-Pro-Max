import type { NextRequest, NextResponse } from "next/server";
import { handleOp } from "@/lib/admin/op-server";

export async function POST(req: NextRequest): Promise<NextResponse> {
  return handleOp(req);
}
