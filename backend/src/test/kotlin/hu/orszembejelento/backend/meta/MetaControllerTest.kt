package hu.orszembejelento.backend.meta

import hu.orszembejelento.backend.common.config.ApiProperties
import hu.orszembejelento.backend.meta.api.MetaController
import hu.orszembejelento.backend.meta.application.GetApiMetaUseCase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * Standalone slice: no Spring context, no database. Verifies the `/api/v1` prefix is
 * actually applied and that the response carries nothing beyond the API identity.
 */
class MetaControllerTest {

    private val properties = ApiProperties(name = "orszem-backend", version = "v1")
    private val mockMvc = MockMvcBuilders
        .standaloneSetup(MetaController(GetApiMetaUseCase(properties)))
        .build()

    @Test
    fun `returns api identity under the v1 prefix`() {
        mockMvc.perform(get("/api/v1/meta"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("orszem-backend"))
            .andExpect(jsonPath("$.apiVersion").value("v1"))
    }

    @Test
    fun `exposes only name and apiVersion`() {
        val body = mockMvc.perform(get("/api/v1/meta"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val keys = Regex("\"(\\w+)\"\\s*:").findAll(body).map { it.groupValues[1] }.toSet()
        check(keys == setOf("name", "apiVersion")) {
            "meta response must expose exactly name and apiVersion, got: $keys"
        }
    }

    @Test
    fun `is not served without the api prefix`() {
        mockMvc.perform(get("/meta")).andExpect(status().isNotFound)
    }
}
