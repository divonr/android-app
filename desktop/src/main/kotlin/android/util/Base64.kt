package android.util

object Base64 {
    const val DEFAULT: Int = 0
    const val NO_WRAP: Int = 2

    fun encodeToString(input: ByteArray, flags: Int): String {
        val encoder = if (flags == NO_WRAP) {
            java.util.Base64.getEncoder().withoutPadding()
        } else {
            java.util.Base64.getEncoder()
        }
        return encoder.encodeToString(input)
    }

    fun decode(input: String, flags: Int): ByteArray {
        val normalized = if (flags == NO_WRAP) input else input.replace("\n", "")
        return java.util.Base64.getDecoder().decode(normalized)
    }
}
