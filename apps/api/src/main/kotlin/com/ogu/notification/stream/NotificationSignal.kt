package com.ogu.notification.stream

/**
 * 인스턴스 사이에 Redis 채널 [CHANNEL]로 주고받는 신호(research R5). 알림 내용은 싣지 않는다. 받는 쪽은 번호로 DB에서
 * 다시 읽으므로 신호를 잃거나 두 번 받아도 알림이 빠지거나 겹치지 않는다.
 *
 * - [New]: `{memberId}:n:{seq}`. 그 회원에게 [seq]번 알림이 커밋됐다. 번호는 따라잡기를 건너뛸지 판단하는 힌트다.
 * - [Read]: `{memberId}:r`. 그 회원의 읽음 상태가 바뀌었다(안 읽은 수를 다시 보낸다).
 */
sealed interface NotificationSignal {
    val memberId: Long

    fun encode(): String

    data class New(
        override val memberId: Long,
        val seq: Long,
    ) : NotificationSignal {
        override fun encode(): String = "$memberId:$NEW:$seq"
    }

    data class Read(
        override val memberId: Long,
    ) : NotificationSignal {
        override fun encode(): String = "$memberId:$READ"
    }

    companion object {
        const val CHANNEL = "ogu:notification"
        private const val NEW = "n"
        private const val READ = "r"

        /** 형식이 맞지 않으면 null이다. 회원 ID와 번호는 0 이상의 10진수만 받는다. */
        fun parse(raw: String): NotificationSignal? {
            val parts = raw.split(':')
            val memberId = parts.firstOrNull()?.toNonNegativeLong() ?: return null
            return when {
                parts.size == 2 && parts[1] == READ -> Read(memberId)
                parts.size == 3 && parts[1] == NEW -> parts[2].toNonNegativeLong()?.let { New(memberId, it) }
                else -> null
            }
        }

        private fun String.toNonNegativeLong(): Long? {
            val digitsOnly = isNotEmpty() && all(Char::isDigit)
            return if (digitsOnly) toLongOrNull() else null
        }
    }
}
