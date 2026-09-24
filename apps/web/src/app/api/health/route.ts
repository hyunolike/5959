import { NextResponse } from "next/server";

import { fetchApiHealth } from "@/shared/api";
import { env } from "@/shared/config";

export const dynamic = "force-dynamic";

export async function GET() {
  return NextResponse.json(await fetchApiHealth(env.API_ORIGIN));
}
