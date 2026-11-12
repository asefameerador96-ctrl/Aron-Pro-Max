import type { NextRequest, NextResponse } from "next/server";
import { handleGiftCreate } from "@/lib/admin/loyalty-server";

export async function POST(req: NextRequest): Promise<NextResponse> {
  return handleGiftCreate(req);
}
