import type { NextRequest, NextResponse } from "next/server";
import { handleTutorialCreate } from "@/components/admin/tutorials-server";

export async function POST(req: NextRequest): Promise<NextResponse> {
  return handleTutorialCreate(req);
}
