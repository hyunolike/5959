import { requestApi, type components } from "@/shared/api";

export type ReadAllResult = components["schemas"]["ReadAllResult"];

/**
 * 같은 출처 BFF 프록시를 거친 읽음 처리. 하나 읽음은 `PUT .../{id}/read`(204, 이미 읽었어도 204)이고,
 * 없거나 다른 회원의 알림이거나 보관 기간이 지났으면 404 `NOTIFICATION_NOT_FOUND`를 `ApiError`로 던진다.
 */
export function markNotificationRead(notificationId: number): Promise<void> {
  return requestApi<void>(`/api/notifications/${notificationId}/read`, {
    method: "PUT",
  });
}

/** 모두 읽음. `upToSeq` 이하의 안 읽은 알림만 바꾸고, 바꾼 수와 남은 안 읽은 수를 돌려준다. */
export function markAllNotificationsRead(
  upToSeq: number,
): Promise<ReadAllResult> {
  return requestApi<ReadAllResult>("/api/notifications/read-all", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ upToSeq }),
  });
}
