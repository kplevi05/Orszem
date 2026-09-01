package hu.orszem.auth.domain

import hu.orszem.identity.domain.UserRole

/** Demo v1 capability set (BUSINESS_RULES.md §5), plus the Demo v1.1 addition. */
enum class Capability {
    REPORT_READ_ACTIVE,
    REPORT_ACCEPT,
    REPORT_ARCHIVE,
    ARCHIVE_READ,
    ANALYTICS_READ,

    /**
     * Demo v1.1: permanent deletion for pilot/test-data cleanup.
     *
     * Deliberately NOT part of the base role capability set — it is granted only
     * when the deployment enables demo deletion, so `/service/me` always tells the
     * client the truth about what this environment allows. This is a demo-scoped
     * switch, not the beginning of a production role model.
     */
    REPORT_DELETE,
}

object Capabilities {
    private val baseServiceUserCapabilities = setOf(
        Capability.REPORT_READ_ACTIVE,
        Capability.REPORT_ACCEPT,
        Capability.REPORT_ARCHIVE,
        Capability.ARCHIVE_READ,
        Capability.ANALYTICS_READ,
    )

    fun forRole(role: UserRole, demoDeletionEnabled: Boolean = false): Set<Capability> = when (role) {
        UserRole.SERVICE_USER ->
            if (demoDeletionEnabled) baseServiceUserCapabilities + Capability.REPORT_DELETE
            else baseServiceUserCapabilities
    }
}
