package com.ogu.ai

/**
 * 본문의 뜻을 숫자 묶음(임베딩)으로 바꾼다(007 research R2). 실패하면 [EmbeddingFailed]를 던진다. 재시도는 부르는 쪽
 * (recommend 모듈)이 맡는다. [key]는 로그에 남길 식별자다(예: `POST:12`). 본문과 값은 로그에 남기지 않는다.
 */
interface Embedder {
    /** 지금 쓰는 모델. 이 모델로 만든 값끼리만 견준다. */
    val model: String

    fun embed(
        key: String,
        content: String,
    ): Embedding
}

/** [model]로 만든 값. 다른 모델로 만든 값끼리는 견줄 수 없으므로 모델 이름을 함께 준다. */
class Embedding(
    val model: String,
    val values: FloatArray,
)

/** 임베딩 실패. [kind]는 `post_embedding.last_error`에 남길 분류이며, 공급자의 응답 원문은 담지 않는다. */
class EmbeddingFailed(
    val kind: EmotionAnalysisFailed.Kind,
) : RuntimeException("임베딩 실패: $kind")
