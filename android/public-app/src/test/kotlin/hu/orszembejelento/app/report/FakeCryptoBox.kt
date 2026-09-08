package hu.orszembejelento.app.report

import hu.orszembejelento.app.report.data.crypto.CryptoBox

/** A deterministic, in-memory, non-secure stand-in for [hu.orszembejelento.app.report.data.crypto.KeystoreCryptoBox] - JVM-testable, never used outside tests. */
class FakeCryptoBox(private val failEncrypt: Boolean = false, private val failDecrypt: Boolean = false) : CryptoBox {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        if (failEncrypt) error("simulated encryption failure")
        // Trivial reversible XOR "encryption" - only a distinguishable, round-trippable
        // transform is needed for these tests, never real secrecy.
        return plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    override fun decrypt(blob: ByteArray): ByteArray? {
        if (failDecrypt) return null
        return blob.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}
