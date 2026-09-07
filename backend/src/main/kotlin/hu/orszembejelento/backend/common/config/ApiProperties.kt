package hu.orszembejelento.backend.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Externally configurable API identity. Deliberately carries no secrets: everything
 * here is safe to expose on a public endpoint.
 */
@ConfigurationProperties(prefix = "orszem.api")
data class ApiProperties(
    val name: String = "orszem-backend",
    val version: String = "v1",
)
