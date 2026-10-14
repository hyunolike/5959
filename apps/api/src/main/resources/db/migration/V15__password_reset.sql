-- 015-password-reset: 비밀번호 재설정 (specs/015-password-reset/data-model.md)

-- member 모듈: 재설정 링크. 회원마다 행이 하나이고 새로 만들면 덮어쓴다(research R2).
-- 링크의 값은 저장하지 않는다. SHA-256(16진수)만 둔다
CREATE TABLE password_reset_token
(
  member_id   BIGINT      NOT NULL,
  token_hash  CHAR(64)    NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  used_at     TIMESTAMPTZ,
  CONSTRAINT password_reset_token_pkey PRIMARY KEY (member_id),
  CONSTRAINT password_reset_token_member_id_fkey FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT password_reset_token_hash_key UNIQUE (token_hash),
  CONSTRAINT password_reset_token_expiry_check CHECK (expires_at > created_at)
);

-- 만료된 지 하루가 지난 행을 지우는 정리 주기가 쓴다
CREATE INDEX password_reset_token_expires_at_idx ON password_reset_token (expires_at);
