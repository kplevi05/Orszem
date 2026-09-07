package hu.orszembejelento.backend.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI infrastructure for the `/api/v1` surface.
 *
 * The contract is generated from the code that actually exists, so it grows with each
 * implemented feature. No endpoint is described here before it is implemented.
 *
 * The server entry is the relative `/`, because the same backend is reached both
 * same-origin through the Public Web edge and directly on the API host.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    companion object {
        const val BEARER_SCHEME = "bearerAuth"
    }

    @Bean
    fun orszemOpenApi(properties: ApiProperties): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Őrszem API")
                    .version(properties.version)
                    .description("Őrszem V2 backend. Public HTTP contract, generation v1."),
            )
            .addServersItem(Server().url("/").description("Same origin as the request"))
            .components(
                Components().addSecuritySchemes(
                    BEARER_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        // Opaque, not JWT: deliberately not advertised as a bearerFormat
                        // of JWT, since nothing in the token is client-readable.
                        .description(
                            "Opaque access token issued by the Service authentication API. " +
                                "Send as: Authorization: Bearer at_<session-id>.<secret>",
                        ),
                ),
            )
}
