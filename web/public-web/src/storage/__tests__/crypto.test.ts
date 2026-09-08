import { beforeEach, describe, expect, it } from 'vitest'
import { CRYPTO_STORE, REPORTS_STORE, _resetForTests, openDatabase } from '../db'
import { decryptCredential, encryptCredential } from '../crypto'

beforeEach(async () => {
  // Close any connection the previous test opened before deleting the database - an open
  // connection would otherwise make deleteDatabase hang waiting for a close that never
  // comes (fake-indexeddb persists across tests within one module unless reset like this).
  await _resetForTests()
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.deleteDatabase('orszem-public-reports')
    request.onsuccess = () => resolve()
    request.onerror = () => reject(request.error)
    request.onblocked = () => resolve()
  })
})

describe('IndexedDB schema', () => {
  it('creates exactly the two expected object stores', async () => {
    const db = await openDatabase()
    expect(Array.from(db.objectStoreNames).sort()).toEqual([CRYPTO_STORE, REPORTS_STORE].sort())
    db.close()
  })
})

describe('credential encryption', () => {
  it('round-trips a credential through encrypt then decrypt', async () => {
    const credential = 'pr_' + 'A'.repeat(43)
    const encrypted = await encryptCredential(credential)
    const decrypted = await decryptCredential(encrypted)
    expect(decrypted).toBe(credential)
  })

  it('produces a different IV on every encryption', async () => {
    const credential = 'pr_' + 'B'.repeat(43)
    const ivs = new Set<string>()
    for (let i = 0; i < 10; i++) {
      const encrypted = await encryptCredential(credential)
      const hex = Array.from(new Uint8Array(encrypted.iv))
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('')
      ivs.add(hex)
    }
    expect(ivs.size).toBe(10)
  })

  it('the stored key is not extractable', async () => {
    await encryptCredential('pr_' + 'C'.repeat(43)) // ensures the key exists
    const db = await openDatabase()
    const tx = db.transaction(CRYPTO_STORE, 'readonly')
    const record = await new Promise<{ key: CryptoKey } | undefined>((resolve, reject) => {
      const request = tx.objectStore(CRYPTO_STORE).get('report-credential-key')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    expect(record).toBeDefined()
    expect(record!.key.extractable).toBe(false)
    db.close()
  })

  it('a corrupted ciphertext fails closed (returns null), never throws to the caller', async () => {
    const credential = 'pr_' + 'D'.repeat(43)
    const encrypted = await encryptCredential(credential)
    const tampered = new Uint8Array(encrypted.ciphertext)
    tampered[tampered.length - 1] = (tampered[tampered.length - 1]! + 1) % 256
    const decrypted = await decryptCredential({ ciphertext: tampered.buffer, iv: encrypted.iv })
    expect(decrypted).toBeNull()
  })

  it('decrypting before any key has ever been created returns null rather than throwing', async () => {
    const decrypted = await decryptCredential({ ciphertext: new ArrayBuffer(16), iv: new ArrayBuffer(12) })
    expect(decrypted).toBeNull()
  })
})

describe('no credential ever reaches localStorage or sessionStorage', () => {
  it('encrypting a credential writes nothing to Web Storage', async () => {
    localStorage.clear()
    sessionStorage.clear()
    const credential = 'pr_' + 'E'.repeat(43)
    await encryptCredential(credential)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})
