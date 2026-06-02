package com.example.ApI.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.net.URL

/**
 * Desktop replacement for Coil's AsyncImage.
 * Loads images from file paths or URLs using Skia.
 */
@Composable
fun DesktopAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    var bitmap by remember(model) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(model) { mutableStateOf(false) }

    LaunchedEffect(model) {
        bitmap = null
        error = false
        if (model == null) { error = true; return@LaunchedEffect }
        try {
            val bytes: ByteArray? = when (model) {
                is String -> {
                    when {
                        model.startsWith("http://") || model.startsWith("https://") -> {
                            URL(model).openStream().use { it.readBytes() }
                        }
                        model.isNotEmpty() -> {
                            val file = File(model)
                            if (file.exists()) file.readBytes() else null
                        }
                        else -> null
                    }
                }
                is File -> if (model.exists()) model.readBytes() else null
                is ByteArray -> model
                else -> null
            }
            if (bytes != null && bytes.isNotEmpty()) {
                bitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            } else {
                error = true
            }
        } catch (e: Exception) {
            error = true
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
            error -> Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize()
            )
            else -> {
                // Loading state - show nothing (or a spinner)
            }
        }
    }
}
