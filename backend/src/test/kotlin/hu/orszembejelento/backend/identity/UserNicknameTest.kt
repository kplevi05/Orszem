package hu.orszembejelento.backend.identity

import hu.orszembejelento.backend.identity.domain.NicknameTooLongException
import hu.orszembejelento.backend.identity.domain.UserNickname
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UserNicknameTest {
    @Test
    fun `normalization trims and blank removes while preserving arbitrary Unicode`() {
        check(UserNickname.normalize("  Levente 🚂  ") == "Levente 🚂")
        check(UserNickname.normalize("   ") == null)
        check(UserNickname.normalize(null) == null)
    }

    @Test
    fun `the cap counts Unicode code points rather than UTF-16 units`() {
        check(UserNickname.normalize("🚂".repeat(64)) == "🚂".repeat(64))
        assertThrows<NicknameTooLongException> { UserNickname.normalize("🚂".repeat(65)) }
    }
}
