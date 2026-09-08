package hu.orszembejelento.backend.reports.api

import java.io.ByteArrayInputStream
import java.lang.reflect.Type
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.databind.ObjectMapper

/**
 * Rejects an unrecognised field in a report submission body instead of silently discarding
 * it - the free-text/GPS/`categoryCode` guard the phase brief requires (§13).
 *
 * The application's shared Jackson configuration is deliberately, globally lenient about
 * unknown properties (Jackson's own default on this version - confirmed by testing, not
 * assumed), and stays that way for every other endpoint. Enforcing this here, rather than
 * by flipping that global setting, is what keeps the change scoped to
 * [SubmitReportRequest] alone: this reads the raw body once as a tree, checks its top-level
 * field names against exactly what [SubmitReportRequest] declares, and only then lets the
 * request proceed through Spring's normal (still globally-lenient) message conversion and
 * `@Valid` bean validation, unmodified. A caller that already validated field names this
 * way passing something Spring's own binder then fails to convert (wrong JSON type, for
 * instance) still surfaces as the existing generic `HttpMessageNotReadableException` /
 * `MethodArgumentNotValidException` handling (`ApiExceptionHandler`) - this class adds one
 * more way to reach that same 400 `VALIDATION_ERROR`, not a new response shape.
 */
@ControllerAdvice(assignableTypes = [PublicReportController::class])
class SubmitReportBodyAdvice(private val objectMapper: ObjectMapper) : RequestBodyAdviceAdapter() {

    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = methodParameter.parameterType == SubmitReportRequest::class.java

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        val bytes = inputMessage.body.readBytes()

        val tree = runCatching { objectMapper.readTree(bytes) }.getOrNull()
        if (tree != null && tree.isObject) {
            val unknown = tree.propertyNames().asSequence().filterNot { it in ALLOWED_FIELDS }.toList()
            if (unknown.isNotEmpty()) {
                throw HttpMessageNotReadableException(
                    "unrecognised field(s) in the report submission body: $unknown",
                    inputMessage,
                )
            }
        }
        // Malformed JSON is deliberately left alone here - it is reported the same way it
        // always was, by the normal converter that reads the body next.

        val replayableBody = bytes
        return object : HttpInputMessage {
            override fun getBody() = ByteArrayInputStream(replayableBody)
            override fun getHeaders() = inputMessage.headers
        }
    }

    private companion object {
        // Exactly SubmitReportRequest's declared properties. No free text, no GPS
        // coordinates, and no client-supplied categoryCode are ever accepted (ADR 0008) -
        // the backend derives category from eventTypeCode alone.
        val ALLOWED_FIELDS = setOf(
            "clientSubmissionId", "occurredAt", "trainIdentifier",
            "settlementId", "railwayLineId", "eventTypeCode",
        )
    }
}
