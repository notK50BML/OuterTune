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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.dd3boh.outertune.audio.VisualizerFrame
import kotlin.math.roundToInt

private const val TAG = "LiquidFerrofluidGpu"

/**
 * How much of the real pixel size the raymarch actually runs at, per quality tier, before the
 * finished layer is scaled back up to fill the space it was asked for.
 *
 * Raymarch cost is per-pixel, so this is the single biggest lever available - a bigger one than
 * trimming step or spike counts - and a softened, background-only crown loses little to the
 * resulting blur; it already reads as "underwater". [HIGH] exists because this effect is most at
 * home on a desktop-class GPU (a WSA build on a PC), where the pixel budget the phone tiers are
 * protecting simply isn't the constraint.
 */
enum class FerrofluidQuality(val renderScale: Float, val maxSteps: Int) {
    /** Quarter-resolution, short march. For phones, or when battery matters more than the look. */
    LOW(0.35f, 20),

    /** The previous fixed behaviour, kept as the default so nothing changes without asking. */
    MEDIUM(0.5f, 24),

    /** Native resolution and a long march - for a desktop-class GPU, where this actually shines. */
    HIGH(1.0f, 40),
}

/**
 * Experimental, opt-in alternative to [LiquidShapeStyle.FERROFLUID]'s Canvas polygon: a genuine
 * raymarched scene, the same technique Shadertoy-style GPU ferrofluid/metaball demos use, running
 * as a single AGSL fragment shader rather than a flat 2D shape. Several blobby "spike" spheres are
 * smooth-min blended into one connected crown SDF, marched per pixel, lit with a Fresnel/specular
 * model - the same "genuinely black, sold by the highlight, not the fill" idea the lightweight
 * version uses, just with a real surface normal instead of a 2D gradient standing in for one.
 *
 * Three things about this version are worth knowing:
 *
 * - **The empty space is skipped analytically.** The scene lives entirely inside a known bounding
 *   sphere, so rather than marching from the camera and spending steps crossing vacuum, the ray is
 *   intersected with that sphere in closed form and the march *starts at the surface*. Rays that
 *   miss the sphere cost one quadratic and return immediately. This is the difference between
 *   most pixels being nearly free and most pixels burning the full step budget, and it buys more
 *   than any constant-tweaking does.
 * - **The colour comes from the theme.** Highlight and rim colours are uniforms fed from the
 *   Material 3 scheme, so the crown belongs to whatever palette the app is currently wearing
 *   instead of being hardcoded to one cold blue-grey.
 * - **The crown moves.** Spikes orbit, and breathe outward and back in together (split/converge)
 *   on a slow cycle that bass pushes along, so the shape travels rather than sitting in place
 *   pulsing. See `orbit`/`spread` in [FERROFLUID_GPU_SHADER_SRC].
 *
 * Still real, sustained per-pixel GPU work: up to `maxSteps` scene evaluations per pixel, each
 * blending SPIKES distance fields, plus four more for the normal at the hit point (tetrahedron
 * technique, not six paired samples). Deliberately kept separate from and opt-in alongside the
 * lightweight Canvas version (never replacing it) so battery/thermal impact stays comparable on a
 * real device - which the lightweight polygon was written to avoid needing in the first place.
 */
private const val FERROFLUID_GPU_SHADER_SRC = """
    uniform float2 resolution;
    uniform float time;
    uniform float bass;
    uniform float treble;
    uniform float transient;
    uniform int maxSteps;
    // Material 3 roles, linear-ish sRGB components. highlightColor carries the specular glint and
    // rimColor the Fresnel edge, so the crown reads as part of the current theme rather than a
    // fixed cold grey.
    uniform float3 highlightColor;
    uniform float3 rimColor;
    uniform float3 baseColor;

    const int SPIKES = 5;
    // The ceiling, and a compile-time constant on purpose: AGSL follows GLSL ES 2.0 rules, where a
    // loop's bound has to be a constant expression, so the quality tier cannot be the bound itself.
    // It's applied as an early break inside the loop instead - a dynamic break is allowed, which
    // the surface-hit test below already relies on.
    const int MAX_STEPS = 40;
    const float MAX_DIST = 6.0;
    const float SURF_DIST = 0.008;
    // Everything the scene can ever occupy fits in this sphere about the origin - the spike
    // centres reach at most ~0.75 out and their radii ~0.3, plus headroom for the pool below.
    const float BOUND_RADIUS = 1.45;

    float sminCubic(float a, float b, float k) {
        float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
        return mix(b, a, h) - k * h * (1.0 - h);
    }

    float spikeDist(float3 p, int i, float t) {
        float fi = float(i);

        // Orbit: the whole crown rotates, and each spike also drifts around its own slot, so the
        // arrangement never settles into a fixed wheel.
        float orbit = fi * 6.2831853 / float(SPIKES)
            + t * 0.22
            + sin(t * 0.37 + fi * 2.1) * 0.28;

        // Split/converge: one slow breath pushes every spike outward and draws it back in
        // together, with bass able to shove it wider. This is what makes the shape travel rather
        // than pulse in place - the radius the spikes sit at is itself moving.
        float spread = 0.42
            + 0.20 * sin(t * 0.31)
            + bass * 0.22
            + transient * 0.10;

        float wobble = sin(t * 0.9 + fi * 1.7) * 0.08;
        float2 basePos = float2(cos(orbit), sin(orbit)) * (spread + wobble);

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
        // corner's signed offset already carries the difference a paired sample would have given,
        // so this reaches the same gradient direction for a third less cost.
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

        // Analytic bounding-sphere entry. Solving this quadratic once is far cheaper than the
        // several marching steps it would otherwise take to cross the empty space in front of the
        // scene - and a ray that misses the sphere entirely is done right here, for the cost of a
        // dot product, instead of walking the full step budget out to MAX_DIST.
        float b = dot(rayOrigin, rayDir);
        float c = dot(rayOrigin, rayOrigin) - BOUND_RADIUS * BOUND_RADIUS;
        float discriminant = b * b - c;
        if (discriminant < 0.0) {
            return half4(0.0, 0.0, 0.0, 0.0);
        }
        float sqrtDisc = sqrt(discriminant);
        float entry = -b - sqrtDisc;
        float exit = -b + sqrtDisc;
        if (exit < 0.0) {
            return half4(0.0, 0.0, 0.0, 0.0);
        }
        float dist = max(entry, 0.0);
        float farLimit = min(exit, MAX_DIST);

        bool didHit = false;
        float3 hitPos = rayOrigin;
        for (int i = 0; i < MAX_STEPS; i++) {
            if (i >= maxSteps) {
                break;
            }
            hitPos = rayOrigin + rayDir * dist;
            float d = sceneDist(hitPos, time);
            if (d < SURF_DIST) {
                didHit = true;
                break;
            }
            dist += d;
            // Stop at the far side of the bounding sphere rather than MAX_DIST: past it there is
            // provably nothing left to hit, so any further step is wasted.
            if (dist > farLimit) {
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

        float3 col = baseColor * (0.3 + diffuse * 0.7)
            + highlightColor * specular * 0.9
            + rimColor * fresnel * 0.45
            + treble * 0.06 * highlightColor;

        return half4(col, 1.0);
    }
"""

/**
 * Attempts to create the raymarched ferrofluid [RuntimeShader], or null when it can't run: below
 * Android 13, or if this device's Skia build rejects the AGSL source for any reason. Compilation is
 * attempted once (not per-frame); a hand-written shader failing to compile should fall back to the
 * lightweight version, never crash the player.
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
 * Renders the raymarched ferrofluid via [content] when supported, or calls [fallback] instead (the
 * existing Canvas polygon) when it isn't - so a caller never has to duplicate the support-check
 * logic itself.
 *
 * [highlightColor]/[rimColor]/[baseColor] come from the Material 3 scheme at the call site; see
 * [FERROFLUID_GPU_SHADER_SRC]. [quality] trades resolution and march length against GPU cost.
 */
@Composable
fun FerrofluidGpuOrFallback(
    isActive: Boolean,
    reactiveFrame: VisualizerFrame?,
    highlightColor: Color,
    rimColor: Color,
    baseColor: Color,
    quality: FerrofluidQuality = FerrofluidQuality.MEDIUM,
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
    val currentHighlight by rememberUpdatedState(highlightColor)
    val currentRim by rememberUpdatedState(rimColor)
    val currentBase by rememberUpdatedState(baseColor)
    val renderScale = quality.renderScale

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
            // Measure and draw this Canvas at the quality tier's fraction of the constraints it was
            // actually given, then stretch the finished layer back up to fill them - the shader
            // inside only ever runs over the smaller size. Skipped entirely at scale 1.0 so the
            // HIGH tier doesn't pay for a pointless layer round-trip.
            .then(
                if (renderScale >= 1f) Modifier else Modifier.layout { measurable, constraints ->
                    val scaledWidth = (constraints.maxWidth * renderScale).roundToInt().coerceAtLeast(1)
                    val scaledHeight = (constraints.maxHeight * renderScale).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(scaledWidth, scaledHeight))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.placeWithLayer(0, 0) {
                            scaleX = 1f / renderScale
                            scaleY = 1f / renderScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                    }
                }
            )
    ) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", elapsedSeconds)
        shader.setFloatUniform("bass", currentBass)
        shader.setFloatUniform("treble", currentTreble)
        shader.setFloatUniform("transient", currentTransient)
        shader.setIntUniform("maxSteps", quality.maxSteps)
        shader.setFloatUniform("highlightColor", currentHighlight.red, currentHighlight.green, currentHighlight.blue)
        shader.setFloatUniform("rimColor", currentRim.red, currentRim.green, currentRim.blue)
        shader.setFloatUniform("baseColor", currentBase.red, currentBase.green, currentBase.blue)
        drawRect(brush = ShaderBrush(shader), size = Size(size.width, size.height))
    }
}
