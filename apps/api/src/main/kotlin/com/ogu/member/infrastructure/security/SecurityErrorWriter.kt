package com.ogu.member.infrastructure.security

import com.ogu.shared.error.ErrorCode
import com.ogu.shared.response.ApiResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * 보안 필터 체인에서 끝나는 응답(401, 403)도 컨트롤러 응답과 같은 `ApiResponse` 봉투로 쓴다.
 * 필터에서 난 오류는 `GlobalExceptionHandler`까지 가지 않기 때문이다.
 */
@Component
class SecurityErrorWriter(
    private val jsonMapper: JsonMapper,
) {
    fun write(
        response: HttpServletResponse,
        errorCode: ErrorCode,
    ) {
        response.status = errorCode.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        jsonMapper.writeValue(response.outputStream, ApiResponse.error(errorCode))
    }
}
