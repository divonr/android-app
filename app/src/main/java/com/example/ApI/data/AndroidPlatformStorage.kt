package com.example.ApI.data

import android.content.Context
import android.os.Environment
import java.io.File

class AndroidPlatformStorage(private val context: Context) : PlatformStorage {
    override val filesDir: File get() = context.filesDir
    override val downloadsDir: File? get() =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
}
