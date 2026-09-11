package hu.orszembejelento.service.usermanagement

import hu.orszembejelento.service.auth.data.NetworkModule
import hu.orszembejelento.service.usermanagement.data.CreateUserRequest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact JSON the create-user call puts on the wire.
 *
 * The app deliberately runs kotlinx.serialization with its normal defaults (no
 * `encodeDefaults`), so a field left at its declared default - `globalAreaAccess = false`
 * when the "Minden terület" box is unchecked - is omitted from the body entirely. The
 * backend must apply the Kotlin constructor default for that omitted field (fixed by adding
 * `jackson-module-kotlin`, PR #11). This test is the client half of that contract: if the
 * serialization config ever changes so the field is always sent, that is a deliberate
 * decision that should break this test first.
 */
class CreateUserRequestWireFormatTest {

    private val json = NetworkModule.json

    @Test
    fun `globalAreaAccess is omitted when left at its default of false`() {
        val body = json.encodeToString(
            CreateUserRequest(role = "SERVICE_USER", areaIds = listOf("area-1"), globalAreaAccess = false),
        )

        assertEquals("""{"role":"SERVICE_USER","areaIds":["area-1"]}""", body)
        assertFalse("a default-valued field must not reach the wire", body.contains("globalAreaAccess"))
    }

    @Test
    fun `globalAreaAccess is sent when explicitly true`() {
        val body = json.encodeToString(
            CreateUserRequest(role = "MODERATOR", areaIds = emptyList(), globalAreaAccess = true),
        )

        assertTrue(body.contains(""""globalAreaAccess":true"""))
    }

    @Test
    fun `areaIds is omitted when left at its default of empty`() {
        val body = json.encodeToString(CreateUserRequest(role = "MODERATOR", globalAreaAccess = true))

        assertEquals("""{"role":"MODERATOR","globalAreaAccess":true}""", body)
    }
}
