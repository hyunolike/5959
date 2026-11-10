package com.ogu.shared.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("오구오구 API")
                .description("오구오구 백엔드 REST API")
                .version("v1"),
        )

    /**
     * 018 FR-002: 점검 중(읽기 전용)에는 쓰기 연산이 `503 SERVICE_MAINTENANCE`를 돌려줄 수 있다. 계약은 이것을 모든
     * 쓰기 연산에 적어 두므로(`components.responses.Maintenance`), 컨트롤러마다 적지 않고 여기서 한 번에 붙인다.
     * 이미 자기 503을 적은 연산은 그대로 둔다. 점검 중에도 받는 연산([MAINTENANCE_EXEMPT])에는 붙이지 않는다.
     */
    @Bean
    fun maintenanceResponseCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            openApi.paths.orEmpty().forEach { (path, item) ->
                item.readOperationsMap().forEach { (method, operation) ->
                    val writes = method in WRITE_METHODS && path.startsWith("/api/v1")
                    val exempt = "${method.name} $path" in MAINTENANCE_EXEMPT
                    if (writes && !exempt && operation.responses?.containsKey("503") == false) {
                        operation.responses.addApiResponse("503", maintenanceResponse())
                    }
                }
            }
        }

    private fun maintenanceResponse(): ApiResponse =
        ApiResponse()
            .description("점검 중이라 쓰기를 받지 않는다 (SERVICE_MAINTENANCE). 요청은 반영되지 않았다")
            .addHeaderObject("Retry-After", Header().description("다시 보낼 때까지 기다릴 초").schema(IntegerSchema()))
            .content(
                Content().addMediaType(
                    "application/json",
                    MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ErrorEnvelope")),
                ),
            )

    companion object {
        private val WRITE_METHODS =
            setOf(
                PathItem.HttpMethod.POST,
                PathItem.HttpMethod.PUT,
                PathItem.HttpMethod.PATCH,
                PathItem.HttpMethod.DELETE,
            )

        /**
         * 점검 중에도 받는 쓰기 연산(018 contracts/maintenance.openapi.yaml). T049의 읽기 전용 필터도 이 목록을 쓴다.
         */
        val MAINTENANCE_EXEMPT =
            setOf(
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/oauth/{provider}",
                "POST /api/v1/auth/refresh",
                "POST /api/v1/auth/logout",
                "POST /api/v1/notifications/stream-tickets",
            )
    }
}
