package com.ogu.shared.response

import com.ogu.shared.error.ErrorCode

data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: ErrorResponse?,
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(success = true, data = data, error = null)

        fun error(
            errorCode: ErrorCode,
            message: String? = null,
            retryAfterSeconds: Int? = null,
        ): ApiResponse<Unit> =
            ApiResponse(
                success = false,
                data = null,
                error =
                    ErrorResponse(
                        code = errorCode.name,
                        message = message ?: errorCode.message,
                        retryAfterSeconds = retryAfterSeconds,
                    ),
            )
    }
}

data class ErrorResponse(
    val code: String,
    val message: String,
    val retryAfterSeconds: Int? = null,
)
