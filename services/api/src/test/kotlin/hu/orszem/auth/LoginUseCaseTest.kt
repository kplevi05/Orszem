package hu.orszem.auth

import hu.orszem.audit.AuditAction
import hu.orszem.audit.AuditPort
import hu.orszem.auth.application.LoginCommand
import hu.orszem.auth.application.LoginUseCase
import hu.orszem.auth.infrastructure.IssuedToken
import hu.orszem.auth.infrastructure.JwtService
import hu.orszem.identity.domain.ServiceUser
import hu.orszem.identity.domain.ServiceUserRepository
import hu.orszem.identity.domain.UserRole
import hu.orszem.identity.domain.UserStatus
import hu.orszem.shared.error.InvalidCredentialsException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

class LoginUseCaseTest {

    private val userId = UUID.randomUUID()
    private val user = ServiceUser(userId, "demo.service", "Demo Szolgálat", "HASH", UserRole.SERVICE_USER, UserStatus.ACTIVE)

    private val users = mock<ServiceUserRepository>()
    private val encoder = mock<org.springframework.security.crypto.password.PasswordEncoder>()
    private val jwt = mock<JwtService>()
    private val audit = mock<AuditPort>()
    private val useCase = LoginUseCase(users, encoder, jwt, audit)

    @Test
    fun `successful login issues a token and audits success`() {
        whenever(users.findByUsername("demo.service")).thenReturn(user)
        whenever(encoder.matches("pw", "HASH")).thenReturn(true)
        whenever(jwt.issue(any())).thenReturn(IssuedToken("token", Instant.now().plusSeconds(3600)))

        val result = useCase.execute(LoginCommand("demo.service", "pw"))

        assertThat(result.token).isEqualTo("token")
        verify(audit).record(eq(AuditAction.SERVICE_LOGIN_SUCCESS), any(), anyOrNull(), anyOrNull(), any())
        verify(audit, never()).record(eq(AuditAction.SERVICE_LOGIN_FAILURE), any(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `unknown user is rejected and audited as failure`() {
        whenever(users.findByUsername("ghost")).thenReturn(null)
        whenever(encoder.matches(any(), any())).thenReturn(false)

        assertThatThrownBy { useCase.execute(LoginCommand("ghost", "pw")) }
            .isInstanceOf(InvalidCredentialsException::class.java)
        verify(audit).record(eq(AuditAction.SERVICE_LOGIN_FAILURE), any(), anyOrNull(), anyOrNull(), any())
        verify(jwt, never()).issue(any())
    }

    @Test
    fun `disabled user cannot log in even with the right password`() {
        whenever(users.findByUsername("demo.service")).thenReturn(user.copy(status = UserStatus.DISABLED))
        whenever(encoder.matches("pw", "HASH")).thenReturn(true)

        assertThatThrownBy { useCase.execute(LoginCommand("demo.service", "pw")) }
            .isInstanceOf(InvalidCredentialsException::class.java)
        verify(jwt, never()).issue(any())
    }

    @Test
    fun `wrong password is rejected`() {
        whenever(users.findByUsername("demo.service")).thenReturn(user)
        whenever(encoder.matches("bad", "HASH")).thenReturn(false)

        assertThatThrownBy { useCase.execute(LoginCommand("demo.service", "bad")) }
            .isInstanceOf(InvalidCredentialsException::class.java)
    }
}
