import { ApiError } from "@/shared/api";

const GENERIC_UPDATE_MESSAGE =
  "글을 고치지 못했습니다. 잠시 후 다시 시도해주세요.";
const GENERIC_DELETE_MESSAGE =
  "글을 지우지 못했습니다. 잠시 후 다시 시도해주세요.";

/** 수정 실패. 400은 본문 아래, 나머지는 폼 전체 문구로 보여 준다. */
export function updateErrorOf(error: unknown): {
  field: "content" | "root";
  message: string;
} {
  if (error instanceof ApiError) {
    if (error.code === "NOT_AUTHOR") {
      return { field: "root", message: "내 글만 고칠 수 있어요." };
    }
    if (error.code === "POST_NOT_FOUND") {
      return { field: "root", message: "삭제된 글이에요." };
    }
    if (error.status === 400) {
      return { field: "content", message: error.message };
    }
  }
  return { field: "root", message: GENERIC_UPDATE_MESSAGE };
}

/** 삭제 실패 문구. */
export function deleteErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "NOT_AUTHOR") {
      return "내 글만 지울 수 있어요.";
    }
    if (error.code === "POST_NOT_FOUND") {
      return "이미 삭제된 글이에요.";
    }
  }
  return GENERIC_DELETE_MESSAGE;
}
