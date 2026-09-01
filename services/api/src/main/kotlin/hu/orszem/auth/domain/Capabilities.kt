package hu.orszem.auth.domain

import hu.orszem.identity.domain.UserRole

/** Demo v1 capability set (BUSINESS_RULES.md §5). */
enum class Capability {
    REPORT_READ_ACTIVE,
    REPORT_ACCEPT,
    REPORT_ARCHIVE,
    ARCHIVE_READ,
    ANALYTICS_READ,
}

object Capabilities {
    private val serviceUserCapabilities = Capability.entries.toSet()

    fun forRole(role: UserRole): Set<Capability> = when (role) {
        UserRole.SERVICE_USER -> serviceUserCapabilities
    }
}
