package hu.orszembejelento.backend.identity

import hu.orszembejelento.backend.identity.domain.NicknameInvalidCharactersException
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

    @Test
    fun `characters PostgreSQL cannot store are rejected but valid supplementary characters are kept`() {
        assertThrows<NicknameInvalidCharactersException> { UserNickname.normalize("a\u0000b") }
        assertThrows<NicknameInvalidCharactersException> { UserNickname.normalize("a\uD800b") }
        assertThrows<NicknameInvalidCharactersException> { UserNickname.normalize("\uDC00") }
        assertThrows<NicknameInvalidCharactersException> { UserNickname.normalize("\uDE82\uD83D") }
        // A correctly paired surrogate is one valid code point; other control characters stay allowed.
        check(UserNickname.normalize("a🚂b") == "a🚂b")
        check(UserNickname.normalize("tab\there") == "tab\there")
    }
}
