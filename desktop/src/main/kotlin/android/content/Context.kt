package android.content

import java.io.File

open class Context(
    val filesDir: File
) {
    companion object {
        const val MODE_PRIVATE: Int = 0
    }
}
