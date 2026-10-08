import { ApiError } from "@/shared/api";

const LIKE_FAILED_MESSAGE = "공감하지 못했어요. 잠시 후 다시 시도해주세요.";

/**
 * 공감이나 취소가 실패했을 때 보여 줄 문구. 이미 공감한 상태(409 `ALREADY_LIKED`)는
 * 다시 불러온 서버 값이 그대로 보여 주므로 따로 알리지 않는다.
 */
export function likeErrorMessage(error: unknown): string | null {
  if (!error) return null;
  if (error instanceof ApiError && error.code === "ALREADY_LIKED") return null;
  return LIKE_FAILED_MESSAGE;
}
