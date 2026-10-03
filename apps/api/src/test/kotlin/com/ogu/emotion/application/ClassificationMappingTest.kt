package com.ogu.emotion.application

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.ClassifiedIntensity
import com.ogu.emotion.EmotionType
import com.ogu.emotion.Intensity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `ai` 모듈은 `emotion` 타입을 모른다. 분류 결과의 자체 enum을 `emotion`이 자기 타입으로 바꾼다.
 */
class ClassificationMappingTest {
    @Test
    fun `ai의 감정 분류는 같은 이름의 감정 종류로 바뀐다`() {
        val mapped = ClassifiedEmotion.entries.map { it.toEmotionType() }

        assertThat(mapped).containsExactly(
            EmotionType.ANXIETY,
            EmotionType.LETHARGY,
            EmotionType.LONELINESS,
            EmotionType.SELF_DEPRECATION,
            EmotionType.IRRITATION,
        )
    }

    @Test
    fun `ai의 강도 분류는 같은 이름의 강도로 바뀐다`() {
        val mapped = ClassifiedIntensity.entries.map { it.toIntensity() }

        assertThat(mapped).containsExactly(Intensity.LOW, Intensity.MEDIUM, Intensity.HIGH)
    }
}
