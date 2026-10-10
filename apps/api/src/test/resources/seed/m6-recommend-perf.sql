-- 007-recommend 성능 측정용 시드(T016, SC-003, quickstart "성능 측정").
-- 지우지 않은 모든 글에 임베딩을 채운다. 이미 있던 값은 덮어쓴다. 글이 1만 건쯤 있는 DB에서 돌린다(M3의 m3-perf.sql로
-- 늘릴 수 있다).
--
-- 값은 묶음 200개로 나눠 만든다. 같은 묶음의 글끼리는 코사인 거리가 0.2쯤이고 다른 묶음과는 1에 가깝다. 값이 모두
-- 제각각이면 기준(max-distance) 안의 글이 하나도 없어, 가까운 글을 찾아 조립하는 길을 재지 못한다.
-- 모델 이름은 e2e 프로필의 가짜 임베더와 같게 둔다. 다르면 기동할 때 전부 다시 만들려 한다.
--
--   docker exec -i <postgres 컨테이너> psql -U ogu -d ogu -v ON_ERROR_STOP=1 < apps/api/src/test/resources/seed/m6-recommend-perf.sql
--
-- 테스트 DB 전용이다. 운영 DB에 돌리지 않는다.

BEGIN;

CREATE TEMP TABLE seed_centers ON COMMIT DROP AS
SELECT c, array_agg(random() * 2 - 1 ORDER BY g)::real[] AS v
FROM generate_series(0, 199) c, generate_series(1, 2048) g
GROUP BY c;

INSERT INTO post_embedding (post_id, author_id, status, embedding, model, requested_seq, embedded_seq, attempts,
                            next_attempt_at, requested_at, completed_at)
SELECT p.id, p.author_id, 'DONE',
       -- 중심(분산 1/3)에 흔들림(분산 1/12)을 더한다. 같은 묶음끼리의 코사인 유사도가 0.8쯤 된다
       (SELECT array_agg(ctr.v[g] + (random() - 0.5) ORDER BY g)::real[]::halfvec(2048)
        FROM generate_series(1, 2048) g),
       'fake-embedder', 1, 1, 0, now(), now(), now()
FROM posts p
JOIN seed_centers ctr ON ctr.c = p.id % 200
WHERE p.deleted_at IS NULL
ON CONFLICT (post_id) DO UPDATE
  SET status = 'DONE', embedding = excluded.embedding, model = excluded.model,
      embedded_seq = post_embedding.requested_seq, attempts = 0, last_error = NULL, completed_at = now();

COMMIT;

ANALYZE post_embedding;

SELECT status, count(*) FROM post_embedding GROUP BY status;
