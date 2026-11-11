package com.example.ApI.data

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory

class PasswordEncryption {
    companion object {
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        private const val KEY_ALGORITHM = "AES"
        private const val KEY_LENGTH = 256
        private const val IV_LENGTH = 16
        private const val SALT_LENGTH = 16
        private const val ITERATION_COUNT = 10000
    }

    fun encryptPassword(password: String, masterKey: String): String {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val keySpec = PBEKeySpec(masterKey.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec)
        val secretKey = SecretKeySpec(key.encoded, KEY_ALGORITHM)
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(password.toByteArray())
        return Base64.getEncoder().encodeToString(salt + iv + encrypted)
    }

    fun decryptPassword(encryptedData: String, masterKey: String): String? {
        return try {
            val combined = Base64.getDecoder().decode(encryptedData)
            val salt = combined.sliceArray(0 until SALT_LENGTH)
            val iv = combined.sliceArray(SALT_LENGTH until SALT_LENGTH + IV_LENGTH)
            val encrypted = combined.sliceArray(SALT_LENGTH + IV_LENGTH until combined.size)
            val keySpec = PBEKeySpec(masterKey.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec)
            val secretKey = SecretKeySpec(key.encoded, KEY_ALGORITHM)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            String(cipher.doFinal(encrypted))
        } catch (e: Exception) { e.printStackTrace(); null }
    }
}

/**
 * Desktop adaptation: stores encrypted password in a local file instead of Android EncryptedSharedPreferences.
 */
class ParentalControlManager(private val context: Context) {
    private val encryption = PasswordEncryption()
    private val prefsFile = File(context.filesDir, "parental_control_prefs.txt")

    private fun readPrefs(): Map<String, String> {
        if (!prefsFile.exists()) return emptyMap()
        return try {
            prefsFile.readLines().associate { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            }
        } catch (e: Exception) { emptyMap() }
    }

    private fun writePrefs(prefs: Map<String, String>) {
        prefsFile.writeText(prefs.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    fun setParentalPassword(password: String, deviceId: String) {
        val encryptedPassword = encryption.encryptPassword(password, deviceId)
        val prefs = readPrefs().toMutableMap()
        prefs["parental_password"] = encryptedPassword
        writePrefs(prefs)
    }

    fun verifyParentalPassword(inputPassword: String, deviceId: String): Boolean {
        val storedEncryptedPassword = readPrefs()["parental_password"] ?: return false
        val decryptedPassword = encryption.decryptPassword(storedEncryptedPassword, deviceId)
        return decryptedPassword == inputPassword
    }

    fun hasParentalPassword(): Boolean = readPrefs()["parental_password"] != null

    fun getEncryptedPassword(): String = readPrefs()["parental_password"] ?: ""
}
