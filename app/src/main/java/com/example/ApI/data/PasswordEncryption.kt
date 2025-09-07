package com.example.ApI.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import java.util.*
import android.util.Base64
import android.content.SharedPreferences
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PasswordEncryption {

    companion object {
        private const val TRANSFORMATION = "AES/CBC/PKCS7Padding"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM = "AES"
        private const val KEY_LENGTH = 256
        private const val IV_LENGTH = 16
        private const val SALT_LENGTH = 16
        private const val ITERATION_COUNT = 10000
    }

    /**
     * הצפנת סיסמה עם salt ייחודי
     */
    fun encryptPassword(password: String, masterKey: String): String {
        // יצירת salt אקראי
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)

        // יצירת מפתח מהסיסמה הראשית באמצעות PBKDF2
        val keySpec = PBEKeySpec(masterKey.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = keyFactory.generateSecret(keySpec)
        val secretKey = SecretKeySpec(key.encoded, KEY_ALGORITHM)

        // יצירת IV אקראי
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)

        // הצפנה
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        val encryptedPassword = cipher.doFinal(password.toByteArray())

        // שילוב Salt + IV + נתונים מוצפנים
        val combined = salt + iv + encryptedPassword

        // החזרה כ-Base64 string
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    /**
     * פיענוח סיסמה
     */
    fun decryptPassword(encryptedData: String, masterKey: String): String? {
        try {
            // פיענוח מ-Base64
            val combined = Base64.decode(encryptedData, Base64.DEFAULT)

            // הפרדת Salt, IV ונתונים מוצפנים
            val salt = combined.sliceArray(0..SALT_LENGTH-1)
            val iv = combined.sliceArray(SALT_LENGTH until SALT_LENGTH + IV_LENGTH)
            val encryptedPassword = combined.sliceArray(SALT_LENGTH + IV_LENGTH until combined.size)

            // שחזור המפתח באמצעות PBKDF2
            val keySpec = PBEKeySpec(masterKey.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
            val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val key = keyFactory.generateSecret(keySpec)
            val secretKey = SecretKeySpec(key.encoded, KEY_ALGORITHM)

            val ivSpec = IvParameterSpec(iv)

            // פיענוח
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decryptedBytes = cipher.doFinal(encryptedPassword)

            return String(decryptedBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}

// דוגמה לשימוש
class ParentalControlManager(private val context: Context) {
    private val encryption = PasswordEncryption()
    private val sharedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "parental_control_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * שמירת סיסמת נעילה להורים
     */
    fun setParentalPassword(password: String, deviceId: String) {
        // שימוש במזהה המכשיר כמפתח ראשי (או כל מחרוזת ייחודית אחרת)
        val encryptedPassword = encryption.encryptPassword(password, deviceId)

        sharedPrefs.edit()
            .putString("parental_password", encryptedPassword)
            .apply()
    }

    /**
     * בדיקת סיסמת נעילה
     */
    fun verifyParentalPassword(inputPassword: String, deviceId: String): Boolean {
        val storedEncryptedPassword = sharedPrefs.getString("parental_password", null)
            ?: return false

        val decryptedPassword = encryption.decryptPassword(storedEncryptedPassword, deviceId)

        return decryptedPassword == inputPassword
    }

    /**
     * בדיקה אם קיימת סיסמה
     */
    fun hasParentalPassword(): Boolean {
        return sharedPrefs.getString("parental_password", null) != null
    }

    /**
     * קבלת סיסמה מוצפנת (לשמירה ב-AppSettings)
     */
    fun getEncryptedPassword(): String {
        return sharedPrefs.getString("parental_password", "") ?: ""
    }
}
