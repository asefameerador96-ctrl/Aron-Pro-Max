import type { NextRequest, NextResponse } from "next/server";
import { handleOfferCreate } from "@/lib/admin/offers-server";

export async function POST(req: NextRequest): Promise<NextResponse> {
  return handleOfferCreate(req);
}
