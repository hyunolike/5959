package com.ogu.raid.presentation

import com.ogu.raid.application.RaidLoader
import com.ogu.raid.domain.RaidLiveStore
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

/**
 * e2e 전용(018 T038). 테스트가 DB에 새 보스를 놓은 뒤 부른다. 메모리 저장소를 비우고 기록에서 다시 올린다.
 * 예전에는 Redis의 보스를 지웠다. e2e 프로필에서만 빈이 생기고, 계약에 넣지 않으며, BFF 키가 있어야 한다.
 */
@Hidden
@Profile("e2e")
@RestController
class RaidE2eController(
    private val store: RaidLiveStore,
    private val loader: RaidLoader,
    @Value("\${ogu.auth.bff-key:}") bffKey: String,
) {
    private val expected: ByteArray? = bffKey.takeIf { it.isNotBlank() }?.toByteArray()

    @PostMapping(PATH)
    fun reload(
        @RequestHeader(BFF_KEY_HEADER, required = false) key: String?,
    ): ResponseEntity<Void> {
        val allowed = expected != null && key != null && MessageDigest.isEqual(expected, key.toByteArray())
        if (!allowed) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        store.clear()
        loader.ensureLoaded()
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val PATH = "/api/v1/e2e/raid/reload"
        private const val BFF_KEY_HEADER = "X-Ogu-Bff-Key"
    }
}
