package com.ogu.ai.infrastructure

/**
 * 모델이 쓴 글이 지켜야 하는 모양과 규칙 가운데 AI 친구의 댓글(009)과 글(012)이 함께 쓰는 것. 낱말 목록이라 다 잡지 못한다.
 * 프롬프트가 먼저 막고, 실제 모델의 글을 사람이 읽어 확인한다. 잘못 걸러도 하나를 다시 청할 뿐이라 넓게 잡는다.
 */
internal object GeneratedTextRules {
    private const val MIN_HANGUL_RATIO = 0.5
    private val HANGUL = Regex("[가-힣ㄱ-ㅎㅏ-ㅣ]")

    /** 따옴표로 감싼 답. 모델이 답을 포장한 것이다. */
    val WRAPPED = Regex("^[\"'“”‘’「『].*[\"'“”‘’」』]$", RegexOption.DOT_MATCHES_ALL)

    /** "댓글:", "제목:" 같은 머리말. */
    val HEADER = Regex("^[가-힣A-Za-z ]{1,12}[:：]\\s*\\S")

    /** 주소, 전화번호, 메일, 메신저. */
    val CONTACT =
        listOf(
            Regex("https?://", RegexOption.IGNORE_CASE),
            Regex("www\\.", RegexOption.IGNORE_CASE),
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\\.[A-Za-z]{2,}"),
            Regex("\\b[A-Za-z0-9-]+\\.(com|net|org|io|kr|co\\.kr)\\b", RegexOption.IGNORE_CASE),
            Regex("\\d{2,4}[-.\\s]\\d{3,4}[-.\\s]\\d{4}"),
            // 구분자 없이 이어 쓴 번호와 대표 번호(1588-0000)
            Regex("\\d{9,}"),
            Regex("\\b1[5-9]\\d{2}[-.\\s]?\\d{4}\\b"),
            // 목록에 없는 최상위 도메인의 짧은 주소(bit.ly/x, t.me/x)
            Regex("\\b[A-Za-z0-9-]+\\.[A-Za-z]{2,}/\\S"),
            Regex("오픈\\s*채팅|카톡|카카오톡|연락처|전화번호|디엠|\\bDM\\b", RegexOption.IGNORE_CASE),
        )

    /** 진단과 처방, 약과 치료의 권유. "약속"처럼 다른 낱말의 일부는 걸리지 않게 조사를 붙여 적는다. */
    val MEDICAL =
        Regex(
            "우울증|조울증|공황장애|불안장애|증후군|ADHD|진단|처방|복용|항우울제|수면제|안정제|약물|" +
                "약[을이도]\\s|약\\s*(먹|드시|드세)|병원|정신과|치료|상담\\s*센터|상담을\\s*받|상담\\s*받",
            RegexOption.IGNORE_CASE,
        )

    /** 글자 가운데 한글이 절반을 넘는다. */
    fun mostlyHangul(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        return HANGUL.findAll(text).count().toDouble() / letters > MIN_HANGUL_RATIO
    }

    fun hasContact(text: String): Boolean = CONTACT.any { it.containsMatchIn(text) }
}
