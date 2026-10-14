# Data Model: 비밀번호 재설정 (015-password-reset)

마이그레이션은 `V15__password_reset.sql` 하나다. 올리기 직전에 `origin/main`의 번호를 다시 본다.

## `password_reset_token` (새로, `member` 모듈)

| 열 | 형 | 설명 |
|---|---|---|
| `member_id` | `BIGINT` PK, `REFERENCES member (id)` | 회원마다 행이 하나다. 새로 만들면 덮어쓴다(research R2) |
| `token_hash` | `CHAR(64)` NOT NULL UNIQUE | 토큰의 SHA-256(16진수). 원문은 저장하지 않는다 |
| `created_at` | `TIMESTAMPTZ` NOT NULL | 만든 때 |
| `expires_at` | `TIMESTAMPTZ` NOT NULL | `created_at + ogu.password-reset.ttl`(기본 30분) |
| `used_at` | `TIMESTAMPTZ` NULL | 쓴 때. NULL이면 아직 쓰지 않았다 |

- 색인: `password_reset_token_expires_at_idx (expires_at)`. 정리 주기가 쓴다.
- 쓰기: `UPDATE ... SET used_at = :now WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now RETURNING member_id`.
- 탈퇴하면 `WithdrawalService`가 그 회원의 행을 지운다(FR-018).
- 만료된 지 하루가 지난 행은 `PasswordResetTokenCleanupJob`이 지운다.

## `login_attempt` (그대로, 키만 더한다)

| 키 | 한도 | 넘으면 |
|---|---|---|
| `reset-ip:<sha256(ip)>` | 한 시간에 10번, 막으면 한 시간 | `429 PASSWORD_RESET_THROTTLED` |
| `reset-email:<sha256(email)>` | 한 시간에 3번, 막으면 한 시간 | 응답은 같고 보내지 않는다 |

## 흐름

```text
요청  ─▶ 곳의 한도 ─▶ 이메일의 한도 ─▶ 이메일 가입 회원인가 ─▶ PasswordResetRequested(memberId, requestedAt) ─▶ 204
                                                                   │ (커밋 뒤, 실패하면 다시)
                                                                   ▼
                                           회원을 다시 읽음 ─▶ 토큰 만들어 덮어씀 ─▶ 메일 발송

재설정 ─▶ 비밀번호 규칙 ─▶ 토큰 쓰기 ─▶ 비밀번호 해시 바꿈 ─▶ 세션 모두 지움 ─▶ (커밋 뒤) 로그인 시도 제한 지움 ─▶ 204
```

## 공개 타입과 설정

| 어디 | 더하는 것 |
|---|---|
| `shared/mail` | `MailSender`, `Mail(to, subject, text)`, `MailProperties`(`ogu.mail`), SMTP와 꺼짐과 e2e 구현 |
| `shared/error` | `PASSWORD_RESET_LINK_INVALID`(400), `PASSWORD_RESET_THROTTLED`(429), `PASSWORD_RESET_UNAVAILABLE`(503) |
| `member` | 이벤트 `PasswordResetRequested`(자기 모듈이 받는다) |

| 설정 | 환경 변수 | 기본 |
|---|---|---|
| `ogu.mail.enabled` | `MAIL_ENABLED` | `false` |
| `ogu.mail.from` | `MAIL_FROM` | 없음 |
| `ogu.mail.smtp.host`, `port`, `username`, `password` | `MAIL_SMTP_HOST`, `MAIL_SMTP_PORT`, `MAIL_SMTP_USERNAME`, `MAIL_SMTP_PASSWORD` | 포트만 `587` |
| `ogu.password-reset.link-base` | `OGU_WEB_ORIGIN` | `http://localhost:3000` |
| `ogu.password-reset.ttl` | - | `30m` |
