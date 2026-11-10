-- 018-free-infra: 임베딩 백필의 완료 표지 (specs/018-free-infra/data-model.md 1)

-- recommend 모듈: 모델마다 빠진 글 채우기와 다른 모델 값 다시 요청을 마쳤는지 남긴다.
-- 서버가 아무 때나 다시 떠도 끝난 모델은 모든 글을 다시 훑지 않는다(research R3)
CREATE TABLE embedding_backfill
(
  model        VARCHAR(100) NOT NULL,
  completed_at TIMESTAMPTZ  NOT NULL,
  CONSTRAINT embedding_backfill_pkey PRIMARY KEY (model)
);
