package com.ogu.support

import net.ttddyy.dsproxy.ExecutionInfo
import net.ttddyy.dsproxy.QueryInfo
import net.ttddyy.dsproxy.listener.QueryExecutionListener
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

/**
 * 현재 스레드에서 실행한 JDBC 문장 수를 센다. JPA와 JdbcClient가 같은 DataSource를 쓰므로 둘 다 잡힌다.
 * 다른 스레드(감정 분석 재시도 폴러, 이벤트 리스너)의 쿼리는 세지 않는다.
 */
object QueryCounter {
    private val counter = ThreadLocal<IntArray?>()

    fun <T> count(block: () -> T): Pair<T, Int> {
        val slot = IntArray(1)
        counter.set(slot)
        try {
            return block() to slot[0]
        } finally {
            counter.remove()
        }
    }

    internal fun record(queries: Int) {
        counter.get()?.let { it[0] += queries }
    }
}

/** 앱의 DataSource를 datasource-proxy로 감싸 [QueryCounter]가 문장을 세게 한다. [TestcontainersConfiguration]이 가져온다. */
@TestConfiguration(proxyBeanMethods = false)
class QueryCountConfiguration {
    companion object {
        @Bean
        @JvmStatic
        fun queryCountingDataSourcePostProcessor(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    if (bean is DataSource) {
                        ProxyDataSourceBuilder
                            .create(bean)
                            .name(beanName)
                            .listener(CountingListener)
                            .build()
                    } else {
                        bean
                    }
            }
    }

    private object CountingListener : QueryExecutionListener {
        override fun beforeQuery(
            execInfo: ExecutionInfo,
            queryInfoList: List<QueryInfo>,
        ) = Unit

        override fun afterQuery(
            execInfo: ExecutionInfo,
            queryInfoList: List<QueryInfo>,
        ) = QueryCounter.record(queryInfoList.size)
    }
}
