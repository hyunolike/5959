/**
 * LLM 게이트웨이 모듈. 본문의 감정을 분류하는 {@link com.ogu.ai.EmotionAnalyzer}를 공개한다.
 * 다른 업무 모듈에 의존하지 않으며, 분류 결과는 이 모듈의 자체 enum으로 돌려준다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package com.ogu.ai;
