package com.music.bitchord.ui.classipod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Album art with the signature floor reflection, from Classipod's
 * AlbumReflectiveArt: the art fills its box edge-to-edge (Crop, never
 * letterboxed — letterboxing is what left black bars flanking the
 * mirror), then a flipped copy of the same art, washed top-to-bottom
 * into the background (white on light LCD, black on dark).
 *
 * @param tilt when true the whole unit leans rotateY(-0.12), the Now
 * Playing lean; Cover Flow leaves it flat and tilts per-page instead.
 */
@Composable
internal fun PodReflectiveArt(
    url: Any?,
    lcd: ClassipodTheme.Lcd,
    artSize: Dp,
    reflectH: Dp = 50.dp,
    tilt: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .size(width = artSize, height = artSize + reflectH)
            .graphicsLayer { if (tilt) rotationY = -6.88f },
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier
                .size(artSize)
                .background(lcd.bar),
        )
        Box(modifier = Modifier.size(width = artSize, height = reflectH)) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleY = -1f },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (lcd.dark) ClassipodTheme.REFLECT_DARK_TOP
                                else ClassipodTheme.REFLECT_LIGHT_TOP,
                                if (lcd.dark) ClassipodTheme.REFLECT_DARK_BOTTOM
                                else ClassipodTheme.REFLECT_LIGHT_BOTTOM,
                            ),
                        ),
                    ),
            )
        }
    }
}
