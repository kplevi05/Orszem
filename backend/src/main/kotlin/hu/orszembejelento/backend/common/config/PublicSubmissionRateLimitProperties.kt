package hu.orszembejelento.backend.common.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Abuse control for anonymous Public report creation (decision B6, ADR 0010).
 *
 * A token bucket per source: it holds at most [burst] tokens and gains one every
 * [refillPeriod]. Creating a report costs one token. The approved production policy is a burst
 * of 10 and one token per 20 seconds (about 3 per minute sustained, at most 180 per hour, per
 * source). These are ordinary configuration so they can be tuned against real traffic without
 * a release.
 *
 * The counters live in this process's memory. That is correct for the single-instance
 * deployment this project runs; see ADR 0010 for what running more than one instance needs.
 */
@ConfigurationProperties(prefix = "orszem.public-submission-rate-limit")
data class PublicSubmissionRateLimitProperties(
    val enabled: Boolean = true,

    /** Bucket capacity: how many reports a source may file in a quick burst. */
    val burst: Int = 10,

    /** One token is added this often, up to [burst]. */
    val refillPeriod: Duration = Duration.ofSeconds(20),

    /**
     * Hard bound on the number of sources tracked at once. Beyond it the least valuable
     * entries are evicted, so memory stays bounded whatever the traffic. An evicted source
     * simply starts again with a full bucket.
     */
    val maximumTrackedKeys: Long = 100_000,
) {
    init {
        require(burst >= 1) { "orszem.public-submission-rate-limit.burst must be at least 1" }
        require(!refillPeriod.isZero && !refillPeriod.isNegative) {
            "orszem.public-submission-rate-limit.refill-period must be positive"
        }
        require(maximumTrackedKeys >= 1) {
            "orszem.public-submission-rate-limit.maximum-tracked-keys must be at least 1"
        }
    }
}
