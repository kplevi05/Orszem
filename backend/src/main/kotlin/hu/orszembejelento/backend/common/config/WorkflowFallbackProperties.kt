package hu.orszembejelento.backend.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Backend-controlled, switchable policy for the temporary nationwide UNCLASSIFIED fallback
 * (Nationwide KSH Settlement Fallback phase).
 *
 * While the legally cleared VPE/KTI/GYSEV railway-line dataset remains PENDING, a Public
 * report against a canonical KSH settlement with zero verified active railway-line relations
 * still routes to `UNCLASSIFIED` (see [hu.orszembejelento.backend.routing.application.RoutingService] -
 * that acceptance behaviour is unconditional and was already correct before this property
 * existed). What this property controls is narrower and *operational*, not routing: whether an
 * ordinary ACTIVE `SERVICE_USER` (not just a global MODERATOR/SUPER_ADMIN) may see and act on
 * `UNCLASSIFIED` reports through the normal NEW/IN_PROGRESS queues, so KSH-only reports stay
 * actionable while territorial routing data is unavailable.
 *
 * Deliberately a single backend switch, never a per-client build flag: flipping it (an
 * environment-variable change, no APK/web release) is how the fallback is turned off again
 * once real railway-line coverage lands - see [ReportWorkflowPolicy][hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy]
 * for exactly which decisions it changes, and [hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportWorkflowQueryRepository]
 * for the matching queue-visibility SQL.
 *
 * Defaults to `false`: merging this change must not silently alter production authorization.
 */
@ConfigurationProperties(prefix = "orszem.workflow")
data class WorkflowFallbackProperties(
    val unclassifiedServiceUserAccessEnabled: Boolean = false,
)
