import { NextResponse } from "next/server";

import { fetchApiLiveness } from "@/shared/api";
import { env } from "@/shared/config";

export const dynamic = "force-dynamic";

/**
 * 들어오는 화면이 API가 깨어 있는지 묻는 주소(018 US2-AC7). API의 liveness만 보므로 잠든 DB를 깨우지 않는다(US2-AC3).
 * `/api/health`는 DB까지 보는 전체 헬스이고 배포 확인 스크립트가 쓰므로 그대로 둔다.
 */
export async function GET() {
  return NextResponse.json(await fetchApiLiveness(env.API_ORIGIN));
}
