package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.uuid.Uuid

/** Only chat content provides a source; global image generation and utility pages provide none. */
val LocalBrowserChatSource = staticCompositionLocalOf<Uuid?> { null }
