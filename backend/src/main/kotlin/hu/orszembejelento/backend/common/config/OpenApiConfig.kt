package hu.orszembejelento.backend.common.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
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
}
