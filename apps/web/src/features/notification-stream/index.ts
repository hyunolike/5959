export { fetchStreamTicket, openStream } from "./api/connect";
export type {
  StreamConnection,
  StreamHandlers,
  StreamTicket,
} from "./api/connect";
export {
  BACKOFF_BASE_MS,
  BACKOFF_JITTER,
  BACKOFF_MAX_MS,
  backoffDelayMs,
} from "./model/backoff";
export {
  applyNotificationEvent,
  applyUnreadCountEvent,
} from "./model/cache-sync";
export {
  createNotificationStreamStore,
  notificationStreamStore,
  stopNotificationStream,
  useNotificationStreamLastEventId,
  useNotificationStreamStatus,
} from "./model/store";
export type { StreamDeps, StreamState, StreamStatus } from "./model/store";
export { useNotificationStream } from "./model/use-notification-stream";
