package com.example.ApI.data

import java.io.File

interface PlatformStorage {
    val filesDir: File
    val downloadsDir: File?
}
