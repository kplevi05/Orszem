package hu.orszembejelento.service.auth.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the app persists between launches.
 *
 * The refresh token only. The access token is deliberately absent — it lives in memory and
 * dies with the process — and no password is ever stored, not even the temporary one.
 */
@Serializable
data class StoredSession(
    val refreshToken: String,
    val sessionExpiresAt: String,
)

/**
 * Authenticated encryption, abstracted away from Android.
 *
 * The production implementation is backed by a non-exportable Android Keystore key. This
 * interface exists so the store's format, versioning and corruption handling can be tested
 * on a plain JVM, where no Keystore exists — without weakening the real cryptography, which
 * stays exactly as it is.
 */
interface CryptoBox {
    /** Returns a self-contained blob: nonce plus ciphertext plus authentication tag. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Returns null if the blob is corrupt, truncated, or not decryptable with this key. */
    fun decrypt(blob: ByteArray): ByteArray?
}

/**
 * Encrypted, versioned storage for the refresh token.
 *
 * Layout: a one-byte format version followed by the [CryptoBox] blob. The version byte is
 * what makes a future format change possible without guessing at the contents of an old
 * file: an unrecognised version is discarded rather than misparsed.
 *
 * Any failure — missing file, unreadable file, wrong version, failed decryption, malformed
 * JSON — is treated the same way: clear the stored data and require a fresh sign-in. A
 * Keystore key can legitimately disappear (device restore, credentials reset, app data
 * cleared), and the app must ask for a password again rather than crash-loop on every start.
 */
class EncryptedTokenStore(
    private val file: File,
    private val crypto: CryptoBox,
) {

    fun load(): StoredSession? {
        val blob = runCatching { if (file.exists()) file.readBytes() else null }.getOrNull() ?: return null
        if (blob.size < 2 || blob[0] != FORMAT_VERSION) {
            clear()
            return null
        }

        val plaintext = crypto.decrypt(blob.copyOfRange(1, blob.size))
        if (plaintext == null) {
            // Undecryptable: the key is gone or the file was tampered with. Either way it
            // is useless, so remove it instead of retrying forever.
            clear()
            return null
        }

        return runCatching {
            json.decodeFromString<StoredSession>(String(plaintext, Charsets.UTF_8))
        }.getOrElse {
            clear()
            null
        }
    }

    fun save(session: StoredSession) {
        val encoded = json.encodeToString(session)
        val encrypted = crypto.encrypt(encoded.toByteArray(Charsets.UTF_8))

        // Write then rename, so a crash mid-write cannot leave a half-written file that
        // would look like corruption on the next launch.
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(byteArrayOf(FORMAT_VERSION) + encrypted)
        if (!temporary.renameTo(file)) {
            file.writeBytes(byteArrayOf(FORMAT_VERSION) + encrypted)
            temporary.delete()
        }
    }

    fun clear() {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
    }

    companion object {
        const val FILE_NAME = "service-auth.bin"
        private const val FORMAT_VERSION: Byte = 1
        private val json = Json { ignoreUnknownKeys = true }
    }
}
