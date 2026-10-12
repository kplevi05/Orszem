package hu.orszembejelento.backend.identity.domain

/**
 * Normalises the optional user-facing nickname.
 *
 * A nickname is deliberately not unique and has no prescribed character set: any
 * Unicode text is accepted. Leading/trailing whitespace is presentation noise and is
 * removed; an empty result means "remove the nickname". The length cap bounds API and
 * audit payloads without imposing a naming convention.
 *
 * The only characters refused are those no PostgreSQL text/jsonb value can hold: NUL, and a
 * UTF-16 surrogate that is not half of a valid pair (not a Unicode scalar value at all).
 * Without this check such input would reach the database and surface as a 500.
 */
object UserNickname {
    const val MAX_CODE_POINTS = 64

    fun normalize(raw: String?): String? {
        val normalized = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        // codePoints() yields a lone surrogate as its own value in 0xD800..0xDFFF; a valid pair
        // is combined into a single supplementary code point and never matches.
        if (normalized.codePoints().anyMatch { it == 0 || it in 0xD800..0xDFFF }) {
            throw NicknameInvalidCharactersException()
        }
        if (normalized.codePointCount(0, normalized.length) > MAX_CODE_POINTS) {
            throw NicknameTooLongException()
        }
        return normalized
    }
}

class NicknameTooLongException :
    RuntimeException("nickname exceeds ${UserNickname.MAX_CODE_POINTS} code points")

class NicknameInvalidCharactersException :
    RuntimeException("nickname contains characters that cannot be stored")
