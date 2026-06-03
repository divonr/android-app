package com.example.ApI.data

import java.io.File

class DesktopPlatformStorage(override val filesDir: File) : PlatformStorage {
    override val downloadsDir: File? get() =
        System.getProperty("user.home")?.let { File(it, "Downloads").takeIf { d -> d.exists() } }
}
