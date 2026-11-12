package com.ogu.shared.schedule

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** `/api/v1/` 아래 요청을 회원의 활동으로 센다. `/actuator/` 아래(헬스, 깨우기)는 세지 않는다. */
@Component
class ActivityFilter(
    private val gate: ActivityGate,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith(API_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        gate.touch()
        filterChain.doFilter(request, response)
    }

    private companion object {
        const val API_PREFIX = "/api/v1/"
    }
}

/**
 * Boot의 `applicationTaskExecutor`에서 도는 일(`@Async`, `@ApplicationModuleListener`)을 회원의 활동으로 센다.
 * `spring.task.execution.mode: force`라 커밋 뒤 리스너가 모두 이 실행기를 탄다.
 *
 * `TaskDecorator` 빈으로 두지 않는다. Boot 4는 그 빈을 `@Scheduled` 스케줄러에도 걸어, 주기 작업(레이드 기록, 정리)까지
 * 활동으로 세게 된다. 그러면 회원이 떠나도 [ActivityGate]가 닫히지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class ActivityExecutorConfig {
    @Bean
    fun activityExecutorCustomizer(gate: ActivityGate): ThreadPoolTaskExecutorCustomizer =
        ThreadPoolTaskExecutorCustomizer { executor ->
            executor.setTaskDecorator { runnable ->
                Runnable {
                    gate.touch()
                    runnable.run()
                }
            }
        }
}
