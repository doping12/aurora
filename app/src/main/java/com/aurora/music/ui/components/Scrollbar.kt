package com.aurora.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun LazyListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp,
) {
    val layoutInfo = state.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val metrics = scrollbarMetrics(layoutInfo.totalItemsCount, visibleItems.firstOrNull()?.index ?: 0,
        visibleItems.size, visibleItems.firstOrNull()?.offset ?: 0, visibleItems.firstOrNull()?.size ?: 0)
    ScrollbarOverlay(
        metrics = metrics,
        isScrolling = state.isScrollInProgress,
        modifier = modifier,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        endPadding = endPadding,
        onScrollTo = { index -> state.scrollToItem(index) },
    )
}

@Composable
fun LazyGridScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp,
) {
    val layoutInfo = state.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val metrics = scrollbarMetrics(layoutInfo.totalItemsCount, visibleItems.firstOrNull()?.index ?: 0,
        visibleItems.size, visibleItems.firstOrNull()?.offset?.y ?: 0, visibleItems.firstOrNull()?.size?.height ?: 0)
    ScrollbarOverlay(
        metrics = metrics,
        isScrolling = state.isScrollInProgress,
        modifier = modifier,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        endPadding = endPadding,
        onScrollTo = { index -> state.scrollToItem(index) },
    )
}

private data class ScrollbarMetrics(
    val totalItems: Int,
    val firstIndex: Int,
    val visibleItems: Int,
    val firstOffset: Int,
    val firstSize: Int,
) {
    val hasRange: Boolean get() = totalItems > visibleItems && visibleItems > 0
    val maxIndex: Int get() = (totalItems - visibleItems).coerceAtLeast(1)
    val fraction: Float
        get() = ((firstIndex + if (firstSize > 0) firstOffset.toFloat() / firstSize else 0f) / maxIndex).coerceIn(0f, 1f)
}

private fun scrollbarMetrics(total: Int, first: Int, visible: Int, offset: Int, size: Int) =
    ScrollbarMetrics(total, first, visible, offset, size)

@Composable
private fun ScrollbarOverlay(
    metrics: ScrollbarMetrics,
    isScrolling: Boolean,
    modifier: Modifier,
    topPadding: Dp,
    bottomPadding: Dp,
    endPadding: Dp,
    onScrollTo: suspend (Int) -> Unit,
) {
    var interacting by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbColor = MaterialTheme.colorScheme.onSurface
    val alpha by animateFloatAsState(if (visible && metrics.hasRange) 0.56f else 0f, label = "scrollbarAlpha")

    LaunchedEffect(metrics.hasRange, isScrolling, interacting) {
        if (!metrics.hasRange) {
            visible = false
        } else if (isScrolling || interacting) {
            visible = true
        } else {
            delay(1_000)
            visible = false
        }
    }

    Box(modifier.fillMaxSize().padding(end = endPadding)) {
        Canvas(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(28.dp)
                .alpha(alpha)
                .pointerInput(metrics, metrics.hasRange) {
                    if (!metrics.hasRange) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val trackTop = topPadding.toPx()
                        val trackHeight = (size.height - trackTop - bottomPadding.toPx()).coerceAtLeast(1f)
                        val thumbHeight = thumbHeight(trackHeight, 32.dp.toPx(), metrics)
                        var thumbTop = trackTop + (trackHeight - thumbHeight) * metrics.fraction
                        if (down.position.y !in thumbTop..(thumbTop + thumbHeight)) return@awaitEachGesture
                        interacting = true
                        down.consume()
                        drag(down.id) { change ->
                            change.consume()
                            thumbTop = (thumbTop + change.positionChange().y)
                                .coerceIn(trackTop, trackTop + trackHeight - thumbHeight)
                            val fraction = if (trackHeight == thumbHeight) 0f
                                else (thumbTop - trackTop) / (trackHeight - thumbHeight)
                            val target = (fraction * metrics.maxIndex).roundToInt()
                                .coerceIn(0, metrics.maxIndex)
                            scope.launch { onScrollTo(target) }
                        }
                        interacting = false
                        visible = true
                    }
                },
        ) {
            val trackTop = topPadding.toPx()
            val trackHeight = (size.height - trackTop - bottomPadding.toPx()).coerceAtLeast(1f)
            val thumbHeight = thumbHeight(trackHeight, 32.dp.toPx(), metrics)
            val thumbTop = trackTop + (trackHeight - thumbHeight) * metrics.fraction
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(size.width - with(density) { 4.dp.toPx() }, thumbTop),
                size = Size(with(density) { 3.dp.toPx() }, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(with(density) { 2.dp.toPx() }),
            )
        }
    }
}

private fun thumbHeight(trackHeight: Float, minPx: Float, metrics: ScrollbarMetrics): Float =
    (trackHeight * metrics.visibleItems / metrics.totalItems.toFloat()).coerceIn(minPx, trackHeight)
