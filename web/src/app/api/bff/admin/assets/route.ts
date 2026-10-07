import type { NextRequest, NextResponse } from "next/server";
import { handleAssetUpload } from "@/components/admin/tutorials-server";

export async function POST(req: NextRequest): Promise<NextResponse> {
  return handleAssetUpload(req);
}
