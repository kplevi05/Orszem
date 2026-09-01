package hu.orszem.reporting.api

import hu.orszem.reporting.application.CreatePublicReportCommand
import hu.orszem.reporting.application.CreatePublicReportUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/public")
class PublicReportController(
    private val createPublicReport: CreatePublicReportUseCase,
) {

    /** `POST /api/v1/public/reports` — anonymous. 201 for a new report, 200 for an idempotent replay. */
    @PostMapping("/reports")
    fun createReport(@Valid @RequestBody request: PublicReportCreateRequest): ResponseEntity<PublicReportCreateResponse> {
        val result = createPublicReport.execute(
            CreatePublicReportCommand(
                id = request.id!!,
                eventTypeCode = request.eventTypeCode!!,
                trainIdentifier = request.trainIdentifier!!,
                settlement = request.settlement!!,
                occurredAt = request.occurredAt!!,
            ),
        )
        val body = PublicReportCreateResponse(
            id = result.id,
            status = "NEW",
            receivedAt = result.receivedAt,
        )
        val status = if (result.idempotentReplay) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(body)
    }
}
