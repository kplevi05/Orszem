package hu.orszembejelento.backend.identity

import hu.orszembejelento.backend.identity.domain.CommonPasswordBlocklist
import hu.orszembejelento.backend.identity.domain.PasswordNormalizer
import hu.orszembejelento.backend.identity.domain.PasswordPolicy
import hu.orszembejelento.backend.identity.domain.PasswordPolicyException
import hu.orszembejelento.backend.identity.domain.PasswordPolicyViolation
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.ServiceIdGenerator
import hu.orszembejelento.backend.identity.domain.TemporaryCredentialGenerator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServiceIdTest {

    @Test
    fun `accepts the canonical form`() {
        check(ServiceId.parseOrNull("SZ-123456")?.value == "SZ-123456")
    }

    @Test
    fun `normalizes surrounding whitespace and case`() {
        check(ServiceId.parseOrNull("  sz-000042  ")?.value == "SZ-000042") {
            "login input should be normalized to canonical form"
        }
    }

    @Test
    fun `rejects anything that is not exactly six digits`() {
        listOf(
            "SZ-12345", "SZ-1234567", "SZ-12345A", "123456", "SZ123456",
            "XX-123456", "", "   ", "SZ-", null,
        ).forEach { input ->
            check(ServiceId.parseOrNull(input) == null) { "should have rejected: $input" }
        }
    }

    @Test
    fun `treats injection-shaped input as data, not syntax`() {
        // The parser is the only gate; such input never reaches SQL as anything but a
        // bound parameter, and it is rejected here as simply malformed.
        listOf(
            "SZ-123456' OR '1'='1",
            "SZ-123456; DROP TABLE users;--",
            "' OR 1=1 --",
        ).forEach { input ->
            check(ServiceId.parseOrNull(input) == null) { "should have rejected: $input" }
        }
    }

    @Test
    fun `generator only produces canonical values`() {
        val generator = ServiceIdGenerator()
        repeat(500) {
            val generated = generator.next()
            check(ServiceId.parseOrNull(generated.value) != null) { "generated invalid id: $generated" }
        }
    }

    @Test
    fun `generator is not sequential`() {
        val generator = ServiceIdGenerator()
        val values = (1..50).map { generator.next().value }
        // A sequential generator would produce a strictly ordered run; a CSPRNG will not.
        check(values != values.sorted()) { "generated ids look sequential: $values" }
        check(values.toSet().size > 40) { "generated ids show too little variety" }
    }
}

class PasswordPolicyTest {

    private val blocklist = CommonPasswordBlocklist.loadDefault()
    private val policy = PasswordPolicy(blocklist)

    private fun violationOf(password: String, current: String? = null): PasswordPolicyViolation =
        assertThrows<PasswordPolicyException> {
            policy.validate(PasswordNormalizer.normalize(password), current)
        }.violation

    @Test
    fun `rejects passwords shorter than fifteen code points`() {
        check(violationOf("a".repeat(14)) == PasswordPolicyViolation.TOO_SHORT)
    }

    @Test
    fun `accepts exactly fifteen code points`() {
        policy.validate(PasswordNormalizer.normalize("uniqueBase!x2Zq"))
    }

    @Test
    fun `rejects passwords longer than 128 code points`() {
        check(violationOf("a1B!".repeat(33)) == PasswordPolicyViolation.TOO_LONG)
    }

    @Test
    fun `accepts a passphrase with spaces and preserves them`() {
        val raw = " vonat kek eg alatt fut "
        val normalized = PasswordNormalizer.normalize(raw)
        policy.validate(normalized)
        check(normalized == raw) { "spaces must be preserved exactly, got: '$normalized'" }
        check(normalized.startsWith(" ") && normalized.endsWith(" ")) {
            "leading and trailing spaces must not be trimmed"
        }
    }

    @Test
    fun `counts code points rather than UTF-16 units`() {
        // Each emoji is a surrogate pair: 15 code points but 30 chars.
        val emoji = "🚀".repeat(15)
        check(emoji.length == 30) { "expected surrogate pairs in this fixture" }
        policy.validate(PasswordNormalizer.normalize(emoji))
    }

    @Test
    fun `normalization is deterministic and makes equivalent unicode forms equal`() {
        // "á" precomposed vs. "a" + combining acute.
        val precomposed = "árvizturo tukorfurogep"
        val decomposed = "árvizturo tukorfurogep"
        check(precomposed != decomposed) { "fixtures should differ before normalization" }

        val a = PasswordNormalizer.normalize(precomposed)
        val b = PasswordNormalizer.normalize(decomposed)
        check(a == b) { "NFC must make canonically equivalent passwords identical" }
        // Idempotent, so verifying a stored password normalizes to the same value again.
        check(PasswordNormalizer.normalize(a) == a)
    }

    @Test
    fun `rejects long but common passwords`() {
        listOf(
            "passwordpassword",
            "PasswordPassword",
            "  passwordpassword  ",
            "qwertyuiopasdfgh",
            "123456789012345",
            "correcthorsebatterystaple",
            "orszembejelento",
        ).forEach { candidate ->
            check(violationOf(candidate) == PasswordPolicyViolation.COMMON_PASSWORD) {
                "should have been blocked as common: $candidate"
            }
        }
    }

    @Test
    fun `rejects a new password equal to the current one`() {
        val current = PasswordNormalizer.normalize("kek vonat fut az egen")
        check(violationOf("kek vonat fut az egen", current) == PasswordPolicyViolation.SAME_AS_CURRENT)
    }

    @Test
    fun `blocklist is small enough to review by hand`() {
        check(blocklist.size in 30..500) {
            "the blocklist is meant to stay curated and reviewable, size was ${blocklist.size}"
        }
    }
}

class TemporaryCredentialTest {

    private val generator = TemporaryCredentialGenerator()

    @Test
    fun `is grouped for transcription and uses the unambiguous alphabet`() {
        repeat(200) {
            val credential = generator.generate()
            check(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(credential)) {
                "unexpected shape: $credential"
            }
            // The digit/letter pairs that are genuinely confused on screen and when
            // dictated: zero with capital O, and one with capital I.
            listOf('0', 'O', '1', 'I').forEach { forbidden ->
                check(!credential.contains(forbidden)) { "$forbidden must not appear in $credential" }
            }
        }
    }

    @Test
    fun `normalization accepts the credential as a human would retype it`() {
        val credential = generator.generate()
        val canonical = TemporaryCredentialGenerator.normalize(credential)

        check(TemporaryCredentialGenerator.normalize(credential.replace("-", "")) == canonical)
        check(TemporaryCredentialGenerator.normalize(credential.lowercase()) == canonical)
        check(TemporaryCredentialGenerator.normalize("  $credential  ") == canonical)
        check(canonical.length == TemporaryCredentialGenerator.LENGTH)
    }

    @Test
    fun `does not repeat`() {
        val generated = (1..2000).map { generator.generate() }.toSet()
        check(generated.size == 2000) { "temporary credentials must not collide" }
    }
}
