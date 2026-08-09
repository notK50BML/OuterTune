/*
 * Copyright (C) 2026 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import com.dd3boh.outertune.models.PlayerLayout
import kotlin.math.roundToInt

/**
 * One block of an imported player layout, placed by coordinate.
 *
 * Must be used inside a container with a bounded size, because everything here is a percentage of
 * it. The block is measured at [PlayerLayout.Block.widthPercent] of the container's width and then
 * placed so that its *centre* lands on ([PlayerLayout.Block.xPercent], [PlayerLayout.Block.yPercent])
 * - centre rather than top-left because that is what the editor writes from schema 3 onwards, and
 * because it is the only anchor that keeps a block where you put it when you resize it.
 *
 * The node reports the container's full size and positions its content inside itself, so several of
 * these stack in one Box without fighting over space, in the order the layout file lists them.
 *
 * Scale and rotation are applied to an inner node, sized to the content, so they turn about the
 * block's own centre instead of the container's.
 */
@Composable
fun FreeBlock(
    block: PlayerLayout.Block,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.layout { measurable, constraints ->
            val width = ((block.widthPercent / 100f) * constraints.maxWidth)
                .roundToInt()
                .coerceIn(1, constraints.maxWidth)
            val placeable = measurable.measure(
                constraints.copy(minWidth = width, maxWidth = width, minHeight = 0)
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = (block.xPercent / 100f) * constraints.maxWidth - placeable.width / 2f
                val y = (block.yPercent / 100f) * constraints.maxHeight - placeable.height / 2f
                placeable.place(x.roundToInt(), y.roundToInt())
            }
        }
    ) {
        Box(modifier = Modifier.blockTransform(block)) { content() }
    }
}

/**
 * One block of an imported layout in the stacked arrangement: no coordinates, but the size and
 * rotation the file asks for still apply.
 */
@Composable
fun StackBlock(
    block: PlayerLayout.Block,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth().blockTransform(block)) { content() }
}

/**
 * Scale and rotation, as a graphics-layer transform.
 *
 * Deliberately a transform and not a re-layout: scaling a block should make it visually bigger
 * without reflowing the text inside it or shoving its neighbours around, which is what the editor's
 * preview shows and what makes a scaled block land where its coordinates say it will.
 */
private fun Modifier.blockTransform(block: PlayerLayout.Block): Modifier {
    val scale = (block.scalePercent / 100f).coerceAtLeast(0.05f)
    if (scale == 1f && block.rotationDegrees == 0f) return this
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        rotationZ = block.rotationDegrees
    }
}
