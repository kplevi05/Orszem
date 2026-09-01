package hu.orszem.analytics.application

import hu.orszem.analytics.domain.AnalyticsQueryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class AnalyticsService(
    private val query: AnalyticsQueryPort,
    private val clock: Clock,
) {
    fun generatedAt(): Instant = clock.instant()

    @Transactional(readOnly = true)
    fun summary() = query.summary()

    @Transactional(readOnly = true)
    fun eventTypeStats() = query.eventTypeStats()

    @Transactional(readOnly = true)
    fun categoryStats() = query.categoryStats()

    @Transactional(readOnly = true)
    fun settlementStats() = query.settlementStats()

    @Transactional(readOnly = true)
    fun trainStats() = query.trainStats()
}
