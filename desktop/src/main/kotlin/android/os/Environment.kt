package android.os

import java.io.File

object Environment {
    const val DIRECTORY_DOWNLOADS: String = "Downloads"

    fun getExternalStoragePublicDirectory(type: String): File {
        val home = System.getProperty("user.home")
        return File(home, type).apply { mkdirs() }
    }
}
