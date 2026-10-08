export { markAllNotificationsRead, markNotificationRead } from "./api/read-api";
export type { ReadAllResult } from "./api/read-api";
export { useMarkAllReadMutation } from "./api/use-mark-all-read-mutation";
export type { MarkAllReadVariables } from "./api/use-mark-all-read-mutation";
export { useMarkReadMutation } from "./api/use-mark-read-mutation";
export {
  knownUpToSeq,
  markAllReadInCache,
  markReadInCache,
} from "./model/read-cache";
export { resolveUpToSeq } from "./model/up-to-seq";
