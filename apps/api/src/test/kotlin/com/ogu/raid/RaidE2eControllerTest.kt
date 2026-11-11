package com.ogu.raid

import com.ogu.emotion.EmotionType
import com.ogu.raid.application.RaidLoader
import com.ogu.raid.domain.RaidBoss
import com.ogu.raid.domain.RaidBossStatus
import com.ogu.raid.domain.RaidLiveStore
import com.ogu.raid.presentation.RaidE2eController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 018 T038: e2e가 새 보스를 놓은 뒤 부르는 다시 올리기. BFF 키가 맞을 때만 저장소를 비우고 기록에서 올린다. */
class RaidE2eControllerTest {
    private val now = Instant.parse("2026-11-11T03:00:00Z")
    private val store = RaidLiveStore(Clock.fixed(now, ZoneOffset.UTC))
    private val loader = mock(RaidLoader::class.java)
    private val controller = RaidE2eController(store, loader, KEY)

    @Test
    fun `키가 맞으면 저장소를 비우고 기록에서 다시 올린다`() {
        store.load(boss(), emptyMap(), epoch = 1)

        val response = controller.reload(KEY)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(store.read()).isNull()
        verify(loader).ensureLoaded()
    }

    @Test
    fun `키가 없거나 다르면 403이고 아무것도 바꾸지 않는다`() {
        store.load(boss(), emptyMap(), epoch = 1)

        assertThat(controller.reload(null).statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(controller.reload("wrong").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(store.read()).isNotNull()
        verify(loader, never()).ensureLoaded()
    }

    @Test
    fun `서버에 키가 없으면 늘 403이다`() {
        val noKey = RaidE2eController(store, loader, "")

        assertThat(noKey.reload("").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    private fun boss() =
        RaidBoss(
            id = 1,
            emotion = EmotionType.LETHARGY,
            maxHp = 10,
            hp = 10,
            status = RaidBossStatus.ALIVE,
            participantCount = 0,
            spawnedAt = now.minusSeconds(60),
            endedAt = null,
            epoch = 0,
        )

    private companion object {
        const val KEY = "e2e-bff-key"
    }
}
