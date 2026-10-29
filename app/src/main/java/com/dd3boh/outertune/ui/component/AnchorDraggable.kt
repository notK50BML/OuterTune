/*
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SNACKBAR_VERY_SHORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One thing a swipe can do.
 *
 * @param label What the in-row indicator says while the drag would do this, e.g. "Play next".
 * @param confirmation What the wide layout's player pane says once it has been done. Null to say
 *   nothing there.
 */
class SwipeAction(
    val icon: ImageVector,
    val label: String,
    val confirmation: String? = null,
    val onSwipe: () -> Unit,
)

/**
 * Swipe to perform an action. This supports one or two actions
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeToQueueBox(
    modifier: Modifier = Modifier,
    item: MediaItem,
    swipeEnabled: Boolean,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    // In the wide layout the player pane says what was queued, right beside the row, so a snackbar
    // at the bottom of the list as well would only be the same sentence twice.
    val snackbars = snackbarHostState.takeIf { LocalSwipeActionFeedback.current == null }

    val title = item.mediaMetadata.title
    SwipeActionBox(
        firstAction = SwipeAction(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            label = resources.getString(R.string.play_next),
            confirmation = resources.getString(R.string.song_added_to_queue, title),
        ) {
            playerConnection?.enqueueNext(item)
            coroutineScope.launch {
                snackbars?.showSnackbar(
                    message = resources.getString(R.string.song_added_to_queue, title),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
            }
        },
        secondAction = SwipeAction(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            label = resources.getString(R.string.swipe_add_to_queue_end),
            confirmation = resources.getString(R.string.song_added_to_queue_end, title),
        ) {
            playerConnection?.enqueueEnd(item)
            coroutineScope.launch {
                val job = launch {
                    snackbars?.showSnackbar(
                        message = resources.getString(R.string.song_added_to_queue_end, title),
                        withDismissAction = true,
                        duration = SnackbarDuration.Indefinite
                    )
                }
                delay(SNACKBAR_VERY_SHORT)
                job.cancel()
            }
        },
        enabled = swipeEnabled,
        modifier = modifier,
        content = content
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeActionBox(
    firstAction: SwipeAction,
    modifier: Modifier = Modifier,
    secondAction: SwipeAction? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box { content() }
    } else {
        BoxWithConstraints() {
            val haptic = LocalHapticFeedback.current
            val coroutineScope = rememberCoroutineScope()
            val feedback = LocalSwipeActionFeedback.current
            // Where this row is on screen, looked up only when a swipe lands, so the wide layout's
            // confirmation can come out level with it. Kept as a plain holder rather than state:
            // nothing draws from it, and a state write on every scroll frame would be wasted.
            val rowCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }

            val swipeOffset = remember { mutableFloatStateOf(0f) }
            val progress = remember { mutableIntStateOf(0) } // swipeOffset but to track haptics and opacity
            val screenWidth = maxWidth
            // These compare a dp figure with an offset in pixels, so a swipe lands after roughly
            // 1/density of the 40% and 80% they read as. That is how far a swipe has always had to
            // go, and what people's thumbs are used to, so it is kept as it is.
            val firstThreshold = (screenWidth * 0.4f).value
            val secondThreshold = (screenWidth * 0.8f).value
            // The row used to stop at the same mixed-unit figure, which on most phones is well under
            // half the screen: too little room for "Add to end of queue" beside it. It may now go
            // on to 60% of the real width. Where letting go does what is unchanged - past the second
            // threshold is the second action however far past.
            val maxOffset = max(screenWidth.value, constraints.maxWidth * 0.6f)
            val draggableState = rememberDraggableState { delta ->
                swipeOffset.floatValue = (swipeOffset.floatValue + delta).coerceIn(0f, maxOffset)
            }

            fun confirm(action: SwipeAction) {
                action.onSwipe()
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                val coordinates = rowCoordinates[0]
                if (feedback != null && action.confirmation != null && coordinates?.isAttached == true) {
                    val top = coordinates.positionInRoot().y
                    feedback.show(action.icon, action.confirmation, top + coordinates.size.height / 2f)
                }
                resetDrag(coroutineScope, swipeOffset)
            }

            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .onPlaced { rowCoordinates[0] = it }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = draggableState,
                        onDragStopped = {
                            when {
                                swipeOffset.floatValue >= secondThreshold -> confirm(secondAction ?: firstAction)
                                swipeOffset.floatValue >= firstThreshold -> confirm(firstAction)
                                else -> resetDrag(coroutineScope, swipeOffset)
                            }
                        }
                    )
            ) {
                // Haptics as the drag crosses each threshold
                if (swipeOffset.floatValue >= firstThreshold) {
                    if (progress.intValue != 1) {
                        if (swipeOffset.floatValue < secondThreshold) {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            progress.intValue = 1
                        }
                    }
                }
                if (swipeOffset.floatValue > 0f) {
                    if (secondAction != null && swipeOffset.floatValue >= secondThreshold) {
                        if (progress.intValue < 2) {
                            haptic.performHapticFeedback(HapticFeedbackType.Reject)
                        }
                        progress.intValue = 2
                    }
                    if (swipeOffset.floatValue < firstThreshold) {
                        progress.intValue = 0
                    }

                    // What letting go now would do. Before the first threshold nothing would, but
                    // the indicator still names the first action, dimmed, so it is clear what the
                    // drag is heading for.
                    val armedSecond = secondAction != null && swipeOffset.floatValue >= secondThreshold
                    val armed = armedSecond || swipeOffset.floatValue >= firstThreshold
                    SwipeIndicator(
                        action = if (armedSecond) secondAction else firstAction,
                        armed = armed,
                        armedColor = if (armedSecond) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        armedContentColor = if (armedSecond) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
                        rowOffset = { swipeOffset.floatValue },
                        modifier = Modifier.matchParentSize()
                    )
                }

                // Foreground draggable content
                Box(
                    modifier = Modifier
                        .offset { IntOffset(swipeOffset.floatValue.roundToInt(), 0) }
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                    content = content
                )
            }
        }
    }
}

private fun resetDrag(scope: CoroutineScope, offset: MutableState<Float>) {
    scope.launch {
        animate(
            initialValue = offset.value,
            targetValue = 0f,
            animationSpec = tween<Float>(durationMillis = 500) // slowSnapAnimationSpec
        ) { value, _ ->
            offset.value = value
        }
    }
}

/** The space left between the dragged row's leading edge and the indicator beside it. */
private val IndicatorGap = 8.dp

/** The indicator stays this far in from the list's edge when it shows its label. */
private val IndicatorInset = 8.dp

/**
 * The indicator that rides along beside a dragged row, in the space the row uncovers as it moves.
 *
 * Its trailing edge keeps a small gap from the row's leading edge, so it reads as being pulled out
 * by the row rather than sitting behind it. It shows the icon alone until the row has uncovered
 * room for the whole label, and then the label as well - a label cut off by the screen's edge
 * would say nothing useful.
 */
@Composable
private fun SwipeIndicator(
    action: SwipeAction,
    armed: Boolean,
    armedColor: Color,
    armedContentColor: Color,
    rowOffset: () -> Float,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (armed) armedColor else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "swipeIndicatorContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (armed) armedContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "swipeIndicatorContent"
    )
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // It stays hidden for the first few dp, so the jitter of a tap or a vertical scroll does not
    // flash it, then grows in from the edge nearest the row.
    val appearStart = with(density) { 16.dp.toPx() }
    val appearLength = with(density) { 32.dp.toPx() }
    val appear = Modifier.graphicsLayer {
        val fraction = ((rowOffset() - appearStart) / appearLength).coerceIn(0f, 1f)
        alpha = fraction
        scaleX = 0.6f + 0.4f * fraction
        scaleY = scaleX
        transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 0.5f)
    }

    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            IndicatorPill(action.icon, action.label, containerColor, contentColor, appear)
            IndicatorPill(action.icon, null, containerColor, contentColor, appear)
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val withLabel = measurables[0].measure(loose)
        val iconOnly = measurables[1].measure(loose)
        val end = rowOffset().roundToInt() - IndicatorGap.roundToPx()
        val room = end - IndicatorInset.roundToPx()
        val pill = if (withLabel.width <= room) withLabel else iconOnly
        layout(constraints.maxWidth, constraints.maxHeight) {
            pill.placeRelative(end - pill.width, (constraints.maxHeight - pill.height) / 2)
        }
    }
}

@Composable
private fun IndicatorPill(
    icon: ImageVector,
    label: String?,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(containerColor, if (label == null) CircleShape else RoundedCornerShape(50))
            .padding(horizontal = if (label == null) 8.dp else 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        if (label != null) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Lets a swipe on a song row tell a host elsewhere on screen what it just did. Only the wide layout
 * provides one: there the player sits in its own pane beside the list, and a short confirmation
 * slides out onto it, level with the row. Everywhere else it is null and the swipe says nothing
 * beyond its own indicator and snackbar.
 */
val LocalSwipeActionFeedback = staticCompositionLocalOf<SwipeActionFeedbackState?> { null }

class SwipeActionFeedback(
    val icon: ImageVector,
    val message: String,
    /** The vertical centre of the swiped row, in root coordinates. */
    val anchorY: Float,
    val key: Long,
)

class SwipeActionFeedbackState {
    /** The last confirmation shown. Kept after it is hidden so it can animate away intact. */
    var feedback by mutableStateOf<SwipeActionFeedback?>(null)
        private set
    var visible by mutableStateOf(false)
        private set
    private var count = 0L

    fun show(icon: ImageVector, message: String, anchorY: Float) {
        feedback = SwipeActionFeedback(icon, message, anchorY, ++count)
        visible = true
    }

    fun hide(key: Long) {
        // A newer swipe may have replaced this one; that one keeps its own full time on screen.
        if (feedback?.key == key) visible = false
    }
}

/** How long a confirmation stays out on the player pane. */
private const val FEEDBACK_SHOWN_MS = 1800L

/**
 * Draws the wide layout's swipe confirmations. Give it the bounds of the player pane: the
 * confirmation slides out from the pane's edge beside the list, onto the player, level with the row
 * that was swiped. It takes no touches, so the player under it works as usual.
 */
@Composable
fun SwipeActionFeedbackHost(
    state: SwipeActionFeedbackState,
    modifier: Modifier = Modifier,
) {
    val feedback = state.feedback
    var hostTop by remember { mutableFloatStateOf(0f) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Out from the list side: the end edge, which is the right in left-to-right layouts.
    val fromEnd = if (rtl) -1 else 1

    LaunchedEffect(feedback?.key) {
        val key = feedback?.key ?: return@LaunchedEffect
        delay(FEEDBACK_SHOWN_MS)
        state.hide(key)
    }

    Layout(
        modifier = modifier.onGloballyPositioned { hostTop = it.positionInRoot().y },
        content = {
            AnimatedVisibility(
                visible = state.visible && feedback != null,
                enter = slideInHorizontally { it * fromEnd / 2 } + fadeIn() + scaleIn(initialScale = 0.85f),
                exit = slideOutHorizontally { it * fromEnd / 2 } + fadeOut() + scaleOut(targetScale = 0.85f),
            ) {
                if (feedback != null) {
                    val shape = RoundedCornerShape(50)
                    val onChip = MaterialTheme.colorScheme.inverseOnSurface
                    // Drawn, not a Surface: a Surface takes every touch on it, and this sits over
                    // the player's own controls for as long as it is out.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            // Read out as it appears, since in this layout it stands in for the snackbar.
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .widthIn(max = 360.dp)
                            .shadow(6.dp, shape)
                            .background(MaterialTheme.colorScheme.inverseSurface, shape)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = feedback.icon,
                            contentDescription = null,
                            tint = onChip,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = feedback.message,
                            color = onChip,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val margin = 12.dp.roundToPx()
        val chip = measurables.firstOrNull()?.measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(0))
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (chip != null && feedback != null) {
                val y = (feedback.anchorY - hostTop - chip.height / 2f).roundToInt()
                    .coerceIn(margin, (constraints.maxHeight - chip.height - margin).coerceAtLeast(margin))
                chip.placeRelative(constraints.maxWidth - chip.width - margin, y)
            }
        }
    }
}
