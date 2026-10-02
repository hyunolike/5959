export interface ApiErrorBody {
  message: string;
  code?: string;
  /** LOGIN_THROTTLED(429)일 때만 있다. */
  retryAfterSeconds?: number;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly retryAfterSeconds?: number;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message);
    this.name = "ApiError";
    this.status = status;
    this.code = body.code;
    this.retryAfterSeconds = body.retryAfterSeconds;
  }
}
