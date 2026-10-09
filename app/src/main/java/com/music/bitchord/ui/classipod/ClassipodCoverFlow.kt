package com.music.bitchord.ui.classipod

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One album in the flow: grouped library tracks sharing an album name. */
data class CoverAlbum(
    val name: String,
    val artist: String,
    val artUrl: String?,
    val songs: List<Song>,
)

/**
 * Cover Flow, from Classipod's BigCoverFlowCarousel: the current album
 * faces you full-size while its neighbours lean away (rotateY ±0.9,
 * shrunk toward 0.6, pivoted on their inner edge). Tap a neighbour to
 * slide to it; tap or press center on the facing album and it zooms
 * and spins into its tracklist. The wheel slides the flow.
 */
@Composable
fun ClassipodCoverFlow(
    albums: List<CoverAlbum>,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    pageKey: Any,
    onSelect: (CoverAlbum) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { albums.size })
    var selecting by remember { mutableIntStateOf(-1) }
    val spin = remember { Animatable(0f) }

    fun slideTo(page: Int) {
        if (selecting >= 0) return
        scope.launch {
            pager.animateScrollToPage(page.coerceIn(0, albums.size - 1))
        }
    }

    fun choose(index: Int) {
        if (selecting >= 0 || index !in albums.indices) return
        selecting = index
        scope.launch {
            spin.snapTo(0f)
            spin.animateTo(1f, tween(380))
            onSelect(albums[index])
            selecting = -1
        }
    }

    wheel.claim(
        pageKey,
        WheelHandlers(
            onStep = { dir -> slideTo(pager.currentPage + dir) },
            onCenter = { choose(pager.currentPage) },
        ),
    )

    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        ClassipodBar(title = "Albums", lcd = lcd, onBack = onBack)
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No albums yet",
                    fontFamily = ClassipodTheme.helveticaBold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = lcd.dim,
                )
            }
            return@Column
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 72.dp),
                pageSpacing = 4.dp,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val rel = page - (pager.currentPage + pager.currentPageOffsetFraction)
                val scale = ((1 - abs(rel)).coerceIn(0.2f, 0.6f)) + 0.4f
                val extra = if (selecting == page) spin.value else 0f
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale * (1 + 0.45f * extra)
                            scaleY = scale * (1 + 0.45f * extra)
                            rotationY = rel * 51.6f + 360f * extra
                            alpha = 1 - 0.45f * extra
                            transformOrigin = if (rel >= 0) TransformOrigin(0f, 0.5f)
                            else TransformOrigin(1f, 0.5f)
                        }
                        .clickable {
                            if (page == pager.currentPage) choose(page)
                            else slideTo(page)
                        },
                ) {
                    PodReflectiveArt(
                        url = albums[page].artUrl,
                        lcd = lcd,
                        artSize = 150.dp,
                        reflectH = 44.dp,
                        tilt = false,
                    )
                }
            }
        }
        val center = albums.getOrNull(pager.currentPage)
        PodMarquee(
            text = center?.name.orEmpty(),
            color = lcd.text,
            fontSize = 14.sp,
            fontFamily = ClassipodTheme.helveticaBold,
            fontWeight = FontWeight.Bold,
            scroll = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Text(
            text = if (albums.isNotEmpty()) {
                "${center?.artist.orEmpty()} · ${pager.currentPage + 1} of ${albums.size}"
            } else "",
            fontFamily = ClassipodTheme.helvetica,
            fontSize = 12.sp,
            color = lcd.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}
