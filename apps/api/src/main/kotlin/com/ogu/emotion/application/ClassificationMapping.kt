package com.ogu.emotion.application

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.ClassifiedIntensity
import com.ogu.emotion.EmotionType
import com.ogu.emotion.Intensity

/** ai 모듈의 분류 결과를 emotion 모듈 타입으로 바꾼다. */
internal fun ClassifiedEmotion.toEmotionType(): EmotionType =
    when (this) {
        ClassifiedEmotion.ANXIETY -> EmotionType.ANXIETY
        ClassifiedEmotion.LETHARGY -> EmotionType.LETHARGY
        ClassifiedEmotion.LONELINESS -> EmotionType.LONELINESS
        ClassifiedEmotion.SELF_DEPRECATION -> EmotionType.SELF_DEPRECATION
        ClassifiedEmotion.IRRITATION -> EmotionType.IRRITATION
    }

internal fun ClassifiedIntensity.toIntensity(): Intensity =
    when (this) {
        ClassifiedIntensity.LOW -> Intensity.LOW
        ClassifiedIntensity.MEDIUM -> Intensity.MEDIUM
        ClassifiedIntensity.HIGH -> Intensity.HIGH
    }
