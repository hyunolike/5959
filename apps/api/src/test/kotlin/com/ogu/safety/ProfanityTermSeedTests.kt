package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.safety.application.TermCache
import com.ogu.safety.application.TextNormalizer
import com.ogu.shared.text.ContentMask
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/** V10__profanity_terms.sql이 더한 욕설과 허용 낱말(005 US5). 문장은 모두 검증용으로 지어낸 것이다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ProfanityTermSeedTests {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var termCache: TermCache

    @Autowired
    lateinit var contentMask: ContentMask

    @BeforeEach
    fun setUp() {
        termCache.refreshNow()
    }

    @Test
    fun `시드의 낱말은 모두 정규화한 꼴이다`() {
        val terms = jdbcTemplate.queryForList("select term from safety_term", String::class.java)

        // 초성은 정규화하면 첫소리 자모가 된다. 보이는 대로 넣으면 글과 견줄 때 맞지 않는다
        assertThat(terms).allSatisfy { assertThat(it).isEqualTo(TextNormalizer.termOf(it)) }
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        아 ㅅㅂ 또 야근|아 ** 또 야근
        ㅆㅂ 진짜|** 진짜
        저 ㅂㅅ이|저 **이
        저 ㅄ이|저 *이
        ㅈㄴ 힘들다|** 힘들다
        ㅈㄹ하네|**하네
        저 ㄱㅅㄲ가|저 ***가
        ㅈ같은 회사|**은 회사""",
    )
    fun `US5-AC1 초성으로 쓴 욕설을 가린다`(
        raw: String,
        expected: String,
    ) {
        assertThat(contentMask.mask(raw)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        존나 힘들다|** 힘들다
        씨바 진짜|** 진짜
        아 시바 또|아 ** 또
        저 새끼가 또|저 **가 또
        미친놈 아니야|*** 아니야
        저 또라이가|저 ***가
        썅 못 해먹겠네|* 못 해먹겠네
        엿먹어라|**어라
        염병하네|**하네
        좀 꺼져라|좀 ***
        fuck 이 회사|**** 이 회사""",
    )
    fun `US5-AC1 새로 더한 욕설을 가린다`(
        raw: String,
        expected: String,
    ) {
        assertThat(contentMask.mask(raw)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        다시 바꿔 달래요
        민수씨 바쁘세요
        민수씨 빨리 와 주세요
        다시 팔로업 하래요
        시바견 사진을 봤어요
        새끼손가락을 다쳤어요
        새끼발가락이 아파요
        전염병이 돌아서 재택했어요
        감염병 대응 회의
        노트북이 꺼져서 작업을 날렸어요
        마감이 닥쳐서 정신이 없어요
        3개년 계획을 다시 쓰래요""",
    )
    fun `US5-AC3 멀쩡한 낱말은 가리지 않는다`(raw: String) {
        assertThat(contentMask.mask(raw)).isEqualTo(raw)
    }
}
