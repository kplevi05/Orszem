package hu.orszem.auth.application

import hu.orszem.audit.AuditAction
import hu.orszem.audit.AuditPort
import hu.orszem.auth.domain.AuthenticatedActor
import hu.orszem.auth.domain.Capabilities
import hu.orszem.auth.infrastructure.IssuedToken
import hu.orszem.auth.infrastructure.JwtService
import hu.orszem.identity.domain.ServiceUser
import hu.orszem.identity.domain.ServiceUserRepository
import hu.orszem.shared.config.OrszemProperties
import hu.orszem.shared.error.InvalidCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

data class LoginCommand(val username: String, val password: String)

@Service
class LoginUseCase(
    private val users: ServiceUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val audit: AuditPort,
    private val properties: OrszemProperties,
) {
    // Non-matching hash used to keep timing similar when the username is unknown.
    private val dummyHash =
        "\$argon2id\$v=19\$m=65536,t=3,p=1\$AAAAAAAAAAAAAAAAAAAAAA\$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    fun execute(command: LoginCommand): IssuedToken {
        val user = users.findByUsername(command.username.trim())
        val passwordOk = passwordEncoder.matches(command.password, user?.passwordHash ?: dummyHash)

        if (user == null || !user.isActive || !passwordOk) {
            audit.record(
                action = AuditAction.SERVICE_LOGIN_FAILURE,
                targetType = "AUTH",
                targetId = user?.id,
                actorUserId = user?.id,
                metadata = mapOf(
                    "username" to command.username.trim(),
                    "reason" to when {
                        user == null -> "USER_NOT_FOUND"
                        !user.isActive -> "USER_DISABLED"
                        else -> "BAD_PASSWORD"
                    },
                ),
            )
            throw InvalidCredentialsException()
        }

        audit.record(AuditAction.SERVICE_LOGIN_SUCCESS, "AUTH", user.id, user.id, mapOf("username" to user.username))
        return jwtService.issue(user.toActor())
    }

    private fun ServiceUser.toActor() = AuthenticatedActor(
        userId = id,
        username = username,
        displayName = displayName,
        role = role.name,
        capabilities = Capabilities.forRole(role, demoDeletionEnabled = properties.demo.deletionEnabled),
    )
}
