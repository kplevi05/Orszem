package hu.orszembejelento.backend.meta.api

import hu.orszembejelento.backend.common.web.ApiPaths
import hu.orszembejelento.backend.meta.application.GetApiMetaUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** HTTP response for [MetaController.meta]. Matches the generated OpenAPI schema. */
data class ApiMetaResponse(
    val name: String,
    val apiVersion: String,
)

/**
 * The reference vertical slice: `api` depends on `application`, never the other way
 * round, and the controller holds no logic of its own beyond mapping.
 */
@RestController
@RequestMapping(ApiPaths.V1)
@Tag(name = "Meta", description = "API identity")
class MetaController(
    private val getApiMeta: GetApiMetaUseCase,
) {

    @GetMapping("/meta")
    @Operation(
        summary = "API identity",
        description = "Returns the API name and HTTP contract generation. Requires no authentication and exposes no internal detail.",
    )
    fun meta(): ApiMetaResponse {
        val meta = getApiMeta.execute()
        return ApiMetaResponse(name = meta.name, apiVersion = meta.apiVersion)
    }
}
