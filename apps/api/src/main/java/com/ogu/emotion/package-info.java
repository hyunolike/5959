/**
 * 감정 분석 모듈. 글이 작성되면 분석을 예약하고, 재시도와 24시간 기본값으로 결과를 끝까지 보장한다(research R2).
 * 결과는 {@link com.ogu.emotion.EmotionAnalyzed} 이벤트로 알리고, 다른 모듈은 {@link com.ogu.emotion.EmotionApi}로 읽는다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "ai"})
package com.ogu.emotion;
