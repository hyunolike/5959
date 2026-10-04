package com.ogu.shared.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.EventPublication
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `ogu.events.resubmit` 설정. [enabled]는 결정적인 순서가 필요한 테스트에서만 끈다.
 * [maxAttempts]는 처음 처리까지 센 시도 횟수 상한이다. 이만큼 실패한 발행은 더는 다시 보내지 않는다.
 */
@ConfigurationProperties("ogu.events.resubmit")
data class EventResubmitProperties(
    val enabled: Boolean = true,
    val interval: Duration = Duration.ofMinutes(1),
    val olderThan: Duration = Duration.ofMinutes(2),
    val maxAttempts: Int = 10,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventResubmitProperties::class)
class EventResubmitConfig

/**
 * 끝나지 않은 이벤트 발행(리스너가 실패했거나 처리 중에 앱이 죽은 것)을 재기동을 기다리지 않고 다시 보낸다.
 * `PostCreated`를 받는 emotion 리스너나 `EmotionAnalyzed`를 받는 MonsterFactory가 한 번 실패해도 글이 PENDING이나
 * 몬스터 없는 상태로 영영 남지 않게 한다. 리스너는 모두 다시 받아도 결과가 같다(멱등).
 * [EventResubmitProperties.olderThan](2분)보다 오래된 것만 보내 지금 처리 중인 발행과 겹치지 않게 한다.
 *
 * 늘 실패하는 리스너를 끝없이 다시 부르지 않도록, 시도 횟수(`event_publication.completion_attempts`, 처음 처리 포함)가
 * [EventResubmitProperties.maxAttempts]에 닿은 발행은 건너뛴다. Spring Modulith 2.1의 `ResubmissionOptions` 필터로
 * 거른다. 건너뛰기 시작한 발행은 이 프로세스에서 한 번만 WARN으로 남기고, 행은 지우지 않아 운영자가 원인을 고친 뒤
 * 직접 다시 보낼 수 있게 둔다.
 */
@Component
class EventPublicationResubmitter(
    private val incompletePublications: IncompleteEventPublications,
    private val properties: EventResubmitProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val givenUp = ConcurrentHashMap.newKeySet<UUID>()

    fun resubmit() {
        incompletePublications.resubmitIncompletePublications(
            ResubmissionOptions
                .defaults()
                .withMinAge(properties.olderThan)
                .withFilter(::withinAttempts),
        )
    }

    private fun withinAttempts(publication: EventPublication): Boolean {
        if (publication.completionAttempts < properties.maxAttempts) return true
        if (givenUp.add(publication.identifier)) {
            log.warn(
                "이벤트 발행이 {}번 실패해 더는 다시 보내지 않습니다: id={}, event={}",
                publication.completionAttempts,
                publication.identifier,
                publication.event.javaClass.simpleName,
            )
        }
        return false
    }

    @Suppress("TooGenericExceptionCaught") // 한 번 실패해도 다음 주기에 다시 돈다
    @Scheduled(
        fixedDelayString = "\${ogu.events.resubmit.interval:1m}",
        initialDelayString = "\${ogu.events.resubmit.interval:1m}",
    )
    fun scheduledResubmit() {
        if (!properties.enabled) return
        try {
            resubmit()
        } catch (e: RuntimeException) {
            log.error("끝나지 않은 이벤트 발행을 다시 보내지 못했습니다", e)
        }
    }
}
