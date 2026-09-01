package hu.orszem.identity.infrastructure

import hu.orszem.identity.domain.ServiceUser
import hu.orszem.identity.domain.ServiceUserRepository
import hu.orszem.identity.domain.UserRole
import hu.orszem.identity.domain.UserStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcServiceUserRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : ServiceUserRepository {

    private val sql = """
        SELECT id, username, display_name, password_hash, role, status
        FROM users
        WHERE %s
    """.trimIndent()

    override fun findByUsername(username: String): ServiceUser? =
        query(sql.format("username = :key"), username)

    override fun findById(id: UUID): ServiceUser? =
        query(sql.format("id = :key::uuid"), id.toString())

    private fun query(statement: String, key: Any): ServiceUser? = jdbc.query(statement, mapOf("key" to key)) { rs, _ ->
        ServiceUser(
            id = rs.getObject("id", UUID::class.java),
            username = rs.getString("username"),
            displayName = rs.getString("display_name"),
            passwordHash = rs.getString("password_hash"),
            role = UserRole.valueOf(rs.getString("role")),
            status = UserStatus.valueOf(rs.getString("status")),
        )
    }.firstOrNull()
}
