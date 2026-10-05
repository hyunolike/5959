package com.ogu.monster

/**
 * 몬스터가 처치됐다(HP 0). 커밋 뒤 비동기로 알린다(M3 알림이 구독). [retroactive]는 몬스터를 만들며 소급 반영으로 처치돼
 * 같은 트랜잭션에서 [MonsterSpawned]가 함께 나갔는지다(004 research R8). 기본값은 이 필드가 생기기 전에 저장된 발행을
 * 다시 읽을 때를 위한 것이다.
 */
data class MonsterDefeated(
    val postId: Long,
    val monsterId: Long,
    val retroactive: Boolean = false,
)
