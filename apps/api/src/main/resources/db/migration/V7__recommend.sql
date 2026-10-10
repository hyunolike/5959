-- 007-recommend: 비슷한 고민 추천 (specs/007-recommend/data-model.md)

CREATE EXTENSION IF NOT EXISTS vector;

-- emotion 모듈: 같은 감정의 최근 글 찾기(research R6)
CREATE INDEX emotion_analysis_emotion_idx ON emotion_analysis (emotion, post_id DESC) WHERE emotion IS NOT NULL;

-- recommend 모듈 -----------------------------------------------------------

-- 글 하나의 임베딩과 처리 일정. posts는 다른 모듈의 테이블이라 FK를 걸지 않는다.
CREATE TABLE post_embedding
(
  post_id          BIGINT NOT NULL,
  -- 보는 사람 자신의 글을 추천에서 뺄 때 쓴다. 글의 작성자는 바뀌지 않는다
  author_id        BIGINT NOT NULL,
  status           VARCHAR(10) NOT NULL,
  -- 마지막으로 만든 값. 고쳐서 다시 기다리는 동안에도 이전 값이 남는다.
  -- 모델의 차원이 2048로 고정이라 vector(색인은 2000차원까지) 대신 halfvec을 쓴다(research R3)
  embedding        halfvec(2048),
  model            VARCHAR(100),
  -- 글이 저장되거나 고쳐질 때마다 오른다. 고친 뒤 늦게 온 이전 결과를 버리는 기준이다
  requested_seq    INT NOT NULL DEFAULT 1,
  -- embedding을 만든 요청의 번호
  embedded_seq     INT,
  attempts         INT NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMPTZ NOT NULL,
  -- 실패의 분류만 남긴다. 공급자의 응답을 담지 않는다
  last_error       VARCHAR(100),
  -- 24시간 기한의 기준. 글을 고치면 그때로 다시 잡는다
  requested_at     TIMESTAMPTZ NOT NULL,
  completed_at     TIMESTAMPTZ,
  CONSTRAINT post_embedding_pkey PRIMARY KEY (post_id),
  CONSTRAINT post_embedding_status_check CHECK (status IN ('PENDING', 'DONE', 'GIVEN_UP')),
  CONSTRAINT post_embedding_done_check CHECK (status <> 'DONE' OR embedding IS NOT NULL),
  CONSTRAINT post_embedding_model_pair_check CHECK ((embedding IS NULL) = (model IS NULL)),
  CONSTRAINT post_embedding_attempts_check CHECK (attempts >= 0)
);

-- 가까운 글 찾기(코사인 거리)
CREATE INDEX post_embedding_hnsw_idx ON post_embedding USING hnsw (embedding halfvec_cosine_ops);
-- 재시도 스케줄러가 차례가 된 PENDING만 훑는다
CREATE INDEX post_embedding_pending_idx ON post_embedding (next_attempt_at) WHERE status = 'PENDING';
