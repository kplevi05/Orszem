package hu.orszem.shared.security

import com.fasterxml.jackson.databind.ObjectMapper
import hu.orszem.shared.error.ErrorCode
import hu.orszem.shared.error.ProblemResponse
import hu.orszem.shared.web.currentCorrelationId
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

/** Renders 401 responses as `application/problem+json` with code `UNAUTHORIZED`. */
@Component
class ProblemAuthenticationEntryPoint(private val objectMapper: ObjectMapper) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) = writeProblem(response, ErrorCode.UNAUTHORIZED, "Érvényes szolgálati bejelentkezés szükséges.", objectMapper)
}

/** Renders 403 (missing capability) — Demo v1 has one capability set, so this is defensive. */
@Component
class ProblemAccessDeniedHandler(private val objectMapper: ObjectMapper) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) = writeProblem(response, ErrorCode.UNAUTHORIZED, "A művelethez nincs jogosultság.", objectMapper)
}

private fun writeProblem(response: HttpServletResponse, code: ErrorCode, detail: String, objectMapper: ObjectMapper) {
    response.status = code.status.value()
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    response.characterEncoding = "UTF-8"
    val body = ProblemResponse(
        title = code.title,
        status = code.status.value(),
        code = code.name,
        detail = detail,
        correlationId = currentCorrelationId(),
    )
    objectMapper.writeValue(response.outputStream, body)
}
