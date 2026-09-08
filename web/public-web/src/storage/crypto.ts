import { getCryptoKeyRecord, putCryptoKeyRecord } from './db'

const IV_LENGTH_BYTES = 12 // 96 bits, the recommended AES-GCM nonce size

/**
 * At-rest hardening for the report access credential (Phase 5 brief §33-34) - **not** an
 * XSS defence; same-origin script can always ask this module to encrypt/decrypt while the
 * page is open, exactly as it can call `fetch` with the credential. What this buys is that
 * the value sitting in IndexedDB between page loads is never the plaintext credential
 * itself, and the AES-GCM key backing it is a non-extractable `CryptoKey` this code never
 * exports or serializes as raw bytes.
 *
 * On any failure - key creation, storage, or the encryption/decryption operation itself -
 * every function here throws or returns null rather than falling back to a plaintext path.
 * There is no plaintext fallback anywhere in this module, by design (§34).
 */
async function getOrCreateKey(): Promise<CryptoKey> {
  const existing = await getCryptoKeyRecord()
  if (existing) return existing.key

  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  await putCryptoKeyRecord({ id: 'report-credential-key', key })
  return key
}

export interface EncryptedCredential {
  readonly ciphertext: ArrayBuffer
  readonly iv: ArrayBuffer
}

/** Encrypts the credential's UTF-8 bytes with a fresh random IV. Throws if anything in the chain fails. */
export async function encryptCredential(credential: string): Promise<EncryptedCredential> {
  const key = await getOrCreateKey()
  const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH_BYTES))
  const plaintext = new TextEncoder().encode(credential)
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext)
  return { ciphertext, iv: iv.buffer }
}

/** Returns null (never throws to the caller) if the key is missing or the ciphertext fails to decrypt/authenticate. */
export async function decryptCredential(encrypted: EncryptedCredential): Promise<string | null> {
  try {
    const existing = await getCryptoKeyRecord()
    if (!existing) return null
    const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: encrypted.iv }, existing.key, encrypted.ciphertext)
    return new TextDecoder().decode(plaintext)
  } catch {
    return null
  }
}
