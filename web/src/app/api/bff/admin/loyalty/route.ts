import type { NextRequest, NextResponse } from "next/server";
import { handleProgrammeCreate } from "@/lib/admin/loyalty-server";

export async function POST(req: NextRequest): Promise<NextResponse> {
  return handleProgrammeCreate(req);
}
