package hu.orszem.catalog

import hu.orszem.catalog.api.EventTypeListResponse
import hu.orszem.shared.error.ProblemResponse
import hu.orszem.support.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

class EventCatalogIT @Autowired constructor(
    private val rest: TestRestTemplate,
) : AbstractIntegrationTest() {

    @Test
    fun `returns the 7 category and 61 event type catalog, anonymously, in canonical order`() {
        val response = rest.getForEntity("/api/v1/public/event-types", EventTypeListResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val items = response.body!!.items
        assertThat(items).hasSize(61)
        assertThat(items.map { it.categoryCode }.distinct()).hasSize(7)

        // ordered by (categorySortOrder, sortOrder, label)
        val sorted = items.sortedWith(compareBy({ it.categorySortOrder }, { it.sortOrder }, { it.label }))
        assertThat(items).containsExactlyElementsOf(sorted)

        assertThat(items.first().code).isEqualTo("FIGHT")
        assertThat(items.first().categoryCode).isEqualTo("VIOLENCE_DANGER")

        val knife = items.single { it.code == "KNIFE_ATTACK" }
        assertThat(knife.label).isEqualTo("Késelés")
        assertThat(knife.categoryLabel).isEqualTo("Erőszak és közvetlen veszély")
    }

    @Test
    fun `protected service endpoint without a token returns problem+json 401 UNAUTHORIZED`() {
        val response = rest.getForEntity("/api/v1/service/reports", ProblemResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.contentType?.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        assertThat(response.body!!.code).isEqualTo("UNAUTHORIZED")
        assertThat(response.body!!.correlationId).isNotBlank()
    }
}
