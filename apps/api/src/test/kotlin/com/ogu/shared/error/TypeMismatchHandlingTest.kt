package com.ogu.shared.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.ConversionNotSupportedException
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 경로 변수와 쿼리 파라미터 형식 오류만 400이고, 변환기가 없는 서버 쪽 문제는 500이다. */
@ExtendWith(OutputCaptureExtension::class)
class TypeMismatchHandlingTest {
    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(ProbeController())
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `경로 변수 형식이 틀리면 400 INVALID_REQUEST다`() {
        mockMvc.get("/probe/abc").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `쿼리 파라미터 형식이 틀리면 400 INVALID_REQUEST다`() {
        mockMvc.get("/probe?size=many").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `변환기가 없어 생긴 ConversionNotSupportedException은 400이 아니라 500이고 ERROR로 남긴다`(output: CapturedOutput) {
        mockMvc.get("/probe/conversion").andExpect {
            status { isInternalServerError() }
            jsonPath("$.error.code") { value("INTERNAL_ERROR") }
        }
        assertThat(output.all).contains("ERROR").contains("ConversionNotSupportedException")
    }

    @RestController
    class ProbeController {
        @GetMapping("/probe/{id:\\d+|abc}")
        fun byId(
            @PathVariable id: Long,
        ): Long = id

        @GetMapping("/probe")
        fun list(
            @RequestParam size: Int,
        ): Int = size

        @GetMapping("/probe/conversion")
        fun conversion(): Unit = throw ConversionNotSupportedException("x", Long::class.java, null)
    }
}
