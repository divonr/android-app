/**
 * SHA-256 hash using the Web Crypto API.
 * Returns lowercase hex string.
 * Used by SettingsPage (child lock setup) and ChildLockPage (password verify).
 */
export async function sha256(text: string): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

/**
 * Random 32-char hex key (16 bytes = AES-128) for share-link encryption.
 * Mirrors ExportImportManager.generateEncryptionKey.
 */
export function generateEncryptionKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

/**
 * AES/CBC/PKCS7 encryption with a hex key; returns Base64(IV ‖ ciphertext).
 * Mirrors ExportImportManager.encryptAES so links open in the same viewer.
 */
export async function encryptAES(plaintext: string, hexKey: string): Promise<string> {
  const keyBytes = new Uint8Array(
    (hexKey.match(/.{2}/g) ?? []).map((h) => parseInt(h, 16)),
  )
  const iv = crypto.getRandomValues(new Uint8Array(16))
  const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-CBC', false, ['encrypt'])
  const encrypted = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-CBC', iv }, key, new TextEncoder().encode(plaintext)),
  )
  const combined = new Uint8Array(iv.length + encrypted.length)
  combined.set(iv)
  combined.set(encrypted, iv.length)
  let binary = ''
  combined.forEach((b) => { binary += String.fromCharCode(b) })
  return btoa(binary)
}
