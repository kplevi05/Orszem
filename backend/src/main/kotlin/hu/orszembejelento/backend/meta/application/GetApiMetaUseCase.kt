package hu.orszembejelento.backend.meta.application

import hu.orszembejelento.backend.common.config.ApiProperties
import org.springframework.stereotype.Service

/**
 * Result of [GetApiMetaUseCase]. An application-layer type: the controller maps it to
 * its own response shape, so the HTTP contract can change without touching this layer.
 */
data class ApiMeta(
    val name: String,
    val apiVersion: String,
)

/**
 * Returns the API's own identity.
 *
 * This is bootstrap infrastructure, not a business feature: it exists so that the
 * `/api/v1` routing, the OpenAPI contract and the Caddy api-prefix proxy can be verified
 * end to end before any real endpoint exists. See docs/architecture/adr/0002.
 */
@Service
class GetApiMetaUseCase(
    private val properties: ApiProperties,
) {
    fun execute(): ApiMeta = ApiMeta(name = properties.name, apiVersion = properties.version)
}
