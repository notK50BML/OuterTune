/*
 * Copyright (C) 2026 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.dd3boh.outertune.audio.VisualizerFrame
import kotlin.math.roundToInt

private const val TAG = "LiquidFerrofluidGpu"

/**
 * Linear fraction of the real pixel size this actually renders at, before [placeWithLayer][
 * androidx.compose.ui.layout.Placeable.PlacementScope.placeWithLayer] scales the result back up
 * to fill the space it was asked for. Raymarch cost is per-pixel, so this is the single biggest
 * lever available - bigger than trimming [SPIKES]/[MAX_STEPS] further would be - and a softened,
 * background-only crown loses little to the resulting blur; it already reads as "underwater".
 */
private const val RENDER_SCALE = 0.5f

/**
 * Experimental, opt-in alternative to [LiquidShapeStyle.FERROFLUID]'s Canvas polygon: a genuine
 * raymarched scene, the same technique Shadertoy-style GPU ferrofluid/metaball demos and
 * WebGL references like artef4kt use, running as a single AGSL fragment shader rather than a
 * flat 2D shape. Several blobby "spike" spheres are smooth-min blended into one connected crown
 * SDF, marched per pixel, lit with a Fresnel/specular model against a dark base - the same
 * "genuinely black, sold by the highlight, not the fill" idea the lightweight version uses, just
 * with a real surface normal instead of a 2D gradient standing in for one.
 *
 * This is real, sustained per-pixel GPU work every frame - up to [MAX_STEPS] scene evaluations
 * per pixel, each evaluating [SPIKES] blended distance fields, plus a further four for the normal
 * at the hit point (the tetrahedron technique, not six paired samples). Rendered at
 * [RENDER_SCALE] of the real pixel size and scaled back up, since raymarch cost scales directly
 * with pixel count and that alone matters more than trimming steps or spikes further would.
 * Deliberately kept separate from and opt-in alongside the lightweight Canvas version (never
 * replacing it) specifically so battery/thermal impact can be compared directly on a real device -
 * which the lightweight polygon was written to avoid needing in the first place.
 */
private const val FERROFLUID_GPU_SHADER_SRC = """
    uniform float2 resolution;
    uniform float time;
    uniform float bass;
    uniform float treble;
    uniform float transient;

    const int SPIKES = 5;
    const int MAX_STEPS = 24;
    const float MAX_DIST = 6.0;
    const float SURF_DIST = 0.008;

    float sminCubic(float a, float b, float k) {
        float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
        return mix(b, a, h) - k * h * (1.0 - h);
    }

    float spikeDist(float3 p, int i, float t) {
        float fi = float(i);
        float angle = fi * 6.2831853 / float(SPIKES) + t * 0.18;
        float wobble = sin(t * 0.9 + fi * 1.7) * 0.1;
        float2 basePos = float2(cos(angle), sin(angle)) * (0.5 + wobble);
        float height = 0.26 + bass * 1.3 + 0.07 * sin(t * 2.1 + fi * 2.3) + transient * 0.5;
        float3 center = float3(basePos.x, -0.2 + height * 0.42, basePos.y);
        float radius = 0.3 - height * 0.07;
        return length(p - center) - radius;
    }

    float sceneDist(float3 p, float t) {
        float pool = length(float3(p.x, (p.y + 0.6) * 2.6, p.z)) - 0.58;
        float d = pool;
        for (int i = 0; i < SPIKES; i++) {
            d = sminCubic(d, spikeDist(p, i, t), 0.32);
        }
        return d;
    }

    float3 estimateNormal(float3 p, float t) {
        // Tetrahedron technique: four corner samples instead of six paired +/- ones - each
        // corner's signed offset already carries the difference a paired sample would have
        // given, so this reaches the same gradient direction for a third less cost, which
        // matters when it runs once per step, [MAX_STEPS] times, once per pixel.
        float2 e = float2(1.0, -1.0) * 0.01;
        return normalize(
            e.xyy * sceneDist(p + e.xyy, t) +
            e.yyx * sceneDist(p + e.yyx, t) +
            e.yxy * sceneDist(p + e.yxy, t) +
            e.xxx * sceneDist(p + e.xxx, t)
        );
    }

    half4 main(float2 fragCoord) {
        float2 uv = (fragCoord - resolution * 0.5) / min(resolution.x, resolution.y);
        if (length(uv) > 0.9) {
            return half4(0.0, 0.0, 0.0, 0.0);
        }

        float3 rayOrigin = float3(0.0, 0.25, -2.2);
        float3 rayDir = normalize(float3(uv.x, -uv.y, 1.0));

        float dist = 0.0;
        bool didHit = false;
        float3 hitPos = rayOrigin;
        for (int i = 0; i < MAX_STEPS; i++) {
            hitPos = rayOrigin + rayDir * dist;
            float d = sceneDist(hitPos, time);
            if (d < SURF_DIST) {
                didHit = true;
                break;
            }
            dist += d;
            if (dist > MAX_DIST) {
                break;
            }
        }

        if (!didHit) {
            return half4(0.0, 0.0, 0.0, 0.0);
        }

        float3 normal = estimateNormal(hitPos, time);
        float3 lightDir = normalize(float3(0.5, 0.8, -0.6));
        float diffuse = max(dot(normal, lightDir), 0.0);
        float3 viewDir = normalize(rayOrigin - hitPos);
        float3 halfDir = normalize(lightDir + viewDir);
        float specular = pow(max(dot(normal, halfDir), 0.0), 46.0);
        float fresnel = pow(1.0 - max(dot(normal, viewDir), 0.0), 3.0);

        float3 base = float3(0.035, 0.035, 0.04);
        float3 col = base * (0.3 + diffuse * 0.7)
            + float3(1.0) * specular * 0.9
            + float3(0.6, 0.65, 0.78) * fresnel * 0.4
            + treble * 0.06 * float3(1.0, 0.3, 0.65);

        return half4(col, 1.0);
    }
"""

/**
 * Attempts to create the raymarched ferrofluid [RuntimeShader], or null when it can't run: below
 * Android 13, or if this device's Skia build rejects the AGSL source for any reason. Compilation
 * is attempted once (not per-frame); a hand-written shader failing to compile should fall back to
 * the lightweight version, never crash the player.
 */
@Composable
private fun rememberFerrofluidGpuShader(): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return remember {
        runCatching { RuntimeShader(FERROFLUID_GPU_SHADER_SRC) }
            .onFailure { Log.e(TAG, "AGSL ferrofluid shader failed to compile, falling back", it) }
            .getOrNull()
    }
}

/**
 * Renders the raymarched ferrofluid via [content] when supported, or calls [fallback] instead
 * (the existing Canvas polygon) when it isn't - so a caller never has to duplicate the
 * support-check logic itself.
 */
@Composable
fun FerrofluidGpuOrFallback(
    isActive: Boolean,
    reactiveFrame: VisualizerFrame?,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val shader = rememberFerrofluidGpuShader()
    if (shader == null) {
        fallback()
        return
    }

    var elapsedSeconds by remember { mutableFloatStateOf(0f) }
    val bass = reactiveFrame?.bass ?: 0f
    val treble = reactiveFrame?.treble ?: 0f
    val transient = reactiveFrame?.transient ?: 0f
    val currentBass by rememberUpdatedState(bass)
    val currentTreble by rememberUpdatedState(treble)
    val currentTransient by rememberUpdatedState(transient)

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    val deltaSeconds = (nanos - lastFrameNanos) / 1_000_000_000f
                    elapsedSeconds += deltaSeconds
                }
                lastFrameNanos = nanos
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Measure and draw this Canvas at RENDER_SCALE of the constraints it was actually
            // given, then stretch the finished layer back up to fill them - the shader inside
            // only ever runs over the smaller size, cutting its pixel count to a quarter at the
            // default 0.5 scale.
            .layout { measurable, constraints ->
                val scaledWidth = (constraints.maxWidth * RENDER_SCALE).roundToInt().coerceAtLeast(1)
                val scaledHeight = (constraints.maxHeight * RENDER_SCALE).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(scaledWidth, scaledHeight))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = 1f / RENDER_SCALE
                        scaleY = 1f / RENDER_SCALE
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }
    ) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", elapsedSeconds)
        shader.setFloatUniform("bass", currentBass)
        shader.setFloatUniform("treble", currentTreble)
        shader.setFloatUniform("transient", currentTransient)
        drawRect(brush = ShaderBrush(shader), size = Size(size.width, size.height))
    }
}
