package hu.orszembejelento.backend.common.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** Tuning for Public report submission validation - configuration, not constants buried in a use case. */
@ConfigurationProperties(prefix = "orszem.reports")
data class ReportSubmissionProperties(
    /**
     * How far into the future `occurredAt` may be before a submission is rejected.
     *
     * A small clock-skew allowance, not a feature: a reporter's device clock can be a few
     * minutes off. Phase 4 deliberately invents no maximum report *age* - only a future
     * bound, since accepting a late report about a real past event is always safe, while
     * accepting one that claims to be from the future never is.
     */
    val occurredAtFutureTolerance: Duration = Duration.ofMinutes(5),

    /** The hard maximum length of a trimmed `trainIdentifier`, in Unicode code points. */
    val trainIdentifierMaxCodePoints: Int = 64,
)
