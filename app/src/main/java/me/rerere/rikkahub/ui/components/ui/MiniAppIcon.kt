package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.rikkahub.data.model.MiniApp

/**
 * Icon for a chat mini app: the user-configured [MiniApp.icon] image when set, otherwise a
 * favicon lookup of the app URL (same fallback used by search provider rows).
 */
@Composable
fun MiniAppIcon(
    app: MiniApp,
    modifier: Modifier = Modifier,
) {
    if (app.icon.isNotBlank()) {
        AsyncImage(
            model = app.icon,
            contentDescription = null,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
            contentScale = ContentScale.Crop,
            placeholder = rememberVectorPainter(HugeIcons.WebDesign01),
            fallback = rememberVectorPainter(HugeIcons.WebDesign01),
        )
    } else {
        Favicon(
            url = app.url,
            modifier = modifier,
        )
    }
}
