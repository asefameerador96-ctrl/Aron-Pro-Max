import type { NextRequest, NextResponse } from "next/server";
import { handleOp } from "@/lib/admin/op-server";

/** Web-role operations (TSO and up): same proxy as admin-op with its own whitelist. */
export async function POST(req: NextRequest): Promise<NextResponse> {
  return handleOp(req, "team");
}
