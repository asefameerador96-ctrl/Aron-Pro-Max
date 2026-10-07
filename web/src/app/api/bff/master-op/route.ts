import type { NextRequest, NextResponse } from "next/server";
import { handleMasterOp } from "@/components/admin/master-op-server";

export async function POST(req: NextRequest): Promise<NextResponse> {
  return handleMasterOp(req);
}
