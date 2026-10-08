package com.ogu.post

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PostPageQueryTest {
    @ParameterizedTest
    @ValueSource(ints = [0, -1, 51])
    fun `size가 1부터 50 밖이면 만들 때 400 INVALID_REQUEST`(size: Int) {
        assertThatThrownBy { PostPageQuery(viewerId = 1, size = size) }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 20, 50])
    fun `size가 1부터 50 안이면 만든다`(size: Int) {
        assertThat(PostPageQuery(viewerId = 1, size = size).size).isEqualTo(size)
    }
}
