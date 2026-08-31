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
enum class FerrofluidQuality(
    val renderScale: Float,
    val maxSteps: Int,
    val targetFps: Int,
    /** How many lattice sites are marched - see `activeSpikes` in [FERROFLUID_GPU_SHADER_SRC]. */
    val activeSpikes: Int,
) {
    /** Quarter-resolution, short march, one ring. For phones, or when battery matters most. */
    LOW(0.35f, 20, 24, 7),

    /** Half-resolution, one ring. The default. */
    MEDIUM(0.5f, 28, 30, 7),

    /** Native resolution, both rings - for a desktop-class GPU, where this actually shines. */
    HIGH(1.0f, 44, 60, 19),

    /** Everything uncapped. Genuinely expensive; for a discrete GPU or a flagship Xclipse/Adreno. */
    ULTRA(1.0f, 64, 60, 19),
}

/**
 * Experimental, opt-in alternative to [LiquidShapeStyle.FERROFLUID]'s Canvas polygon: a genuine
 * raymarched scene running as a single AGSL fragment shader rather than a flat 2D shape.
 *
 * This models what ferrofluid actually does, rather than the generic Shadertoy metaball look:
 *
 * - **The shape is the Rosensweig instability.** Put a ferrofluid pool in a vertical magnetic
 *   field and past a critical strength the flat surface spontaneously breaks into a regular
 *   *hexagonal lattice* of peaks - the packing that best balances surface tension, gravity and the
 *   field. So the scene is a hexagonal array of cusped peaks rising from a shallow pool, with bass
 *   playing the part of field strength, rather than a ring of blobs orbiting each other. See
 *   `latticeSite`.
 * - **The peaks are cones, not spheres.** A real peak has a broad foot and a near-point tip, and
 *   blending spheres can only ever produce mounds. `sdRoundCone` is an exact cone SDF, and the
 *   smin blend radius is deliberately tight so the feet meet the pool in a cusp.
 * - **The material is almost pure specular.** Ferrofluid is magnetite in oil - optically it is
 *   opaque black, and essentially everything visible in real footage is reflection, not diffuse
 *   shading. So diffuse is a trace, and the surface is carried by two sharp highlights, a Schlick
 *   Fresnel edge, and a cheap reflected vertical gradient standing in for the room.
 * - **The empty space is skipped analytically.** The scene lives entirely inside a known bounding
 *   sphere, so rather than marching from the camera and spending steps crossing vacuum, the ray is
 *   intersected with that sphere in closed form and the march *starts at the surface*. Rays that
 *   miss the sphere cost one quadratic and return immediately. This is the difference between
 *   most pixels being nearly free and most pixels burning the full step budget, and it buys more
 *   than any constant-tweaking does.
 * - **The colour comes from the theme.** Highlight and rim colours are uniforms fed from the
 *   Material 3 scheme, so the crown belongs to whatever palette the app is currently wearing
 *   instead of being hardcoded to one cold blue-grey.
 *
 * Still real, sustained per-pixel GPU work: up to `maxSteps` scene evaluations per pixel, each
 * blending `activeSpikes` distance fields, plus four more for the normal at the hit point
 * (tetrahedron technique, not six paired samples). Deliberately kept separate from and opt-in alongside the
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
    uniform int activeSpikes;
    // Material 3 roles, linear-ish sRGB components. highlightColor carries the specular glint and
    // rimColor the Fresnel edge, so the crown reads as part of the current theme rather than a
    // fixed cold grey.
    uniform float3 highlightColor;
    uniform float3 rimColor;
    uniform float3 baseColor;

    // A full two-ring hexagonal lattice: 1 centre + 6 + 12. The tier's activeSpikes decides how
    // many are actually marched (early break in sceneDist), so the cheap tiers render the centre
    // and first ring only - still a hexagon, just a smaller one, rather than a broken lattice.
    const int SPIKES = 19;
    // The ceiling, and a compile-time constant on purpose: AGSL follows GLSL ES 2.0 rules, where a
    // loop's bound has to be a constant expression, so the quality tier cannot be the bound itself.
    // It's applied as an early break inside the loop instead - a dynamic break is allowed, which
    // the surface-hit test below already relies on.
    const int MAX_STEPS = 64;
    const float MAX_DIST = 6.0;
    const float SURF_DIST = 0.008;
    // Everything the scene can ever occupy fits in this sphere about the origin - the spike
    // pool reaches 0.80 in xz and 0.87 below, and the tallest peak tops out near y = 0.35.
    const float BOUND_RADIUS = 1.45;

    float sminCubic(float a, float b, float k) {
        float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
        return mix(b, a, h) - k * h * (1.0 - h);
    }

    // Exact SDF for a vertical rounded cone (Inigo Quilez): base radius r1 at the origin tapering
    // to tip radius r2 at height h. This is the shape that makes the difference - a real peak is a
    // cusp with a broad foot and a near-point, not a sphere, and blending spheres could only ever
    // give mounds.
    float sdRoundCone(float3 p, float r1, float r2, float h) {
        float2 q = float2(length(p.xz), p.y);
        float b = (r1 - r2) / h;
        float a = sqrt(max(1.0 - b * b, 0.0));
        float k = dot(q, float2(-b, a));
        if (k < 0.0) return length(q) - r1;
        if (k > a * h) return length(q - float2(0.0, h)) - r2;
        return dot(q, float2(a, b)) - r1;
    }

    // Rosensweig lattice. A ferrofluid pool in a vertical magnetic field does not throw up a ring
    // of blobs: past a critical field strength the flat surface goes unstable and settles into a
    // regular *hexagonal* array of peaks, because that is the packing that best balances surface
    // tension, gravity and the field. Index 0 is the centre, 1..6 the first ring, and 7..18 the
    // second - six at the corners plus six at the edge midpoints, which is what makes the outer
    // ring part of the same hexagonal packing instead of just a wider circle of peaks.
    float3 latticeSite(int i, float spacing, float spin) {
        if (i == 0) return float3(0.0, 0.0, 0.0);
        float ang;
        float rad;
        if (i <= 6) {
            ang = float(i - 1) * 1.0471976;
            rad = spacing;
        } else if (i <= 12) {
            ang = float(i - 7) * 1.0471976;
            rad = spacing * 2.0;
        } else {
            ang = float(i - 13) * 1.0471976 + 0.5235988;
            rad = spacing * 1.7320508;
        }
        ang += spin;
        return float3(cos(ang) * rad, 0.0, sin(ang) * rad);
    }

    float spikeDist(float3 p, int i, float t) {
        float spacing = 0.26;
        // The lattice turns slowly as a whole. The peaks keep their slots relative to each other,
        // which is the point - a hexagonal array that stays an array reads as a real instability,
        // where independently wandering blobs read as lava lamp.
        float3 site = latticeSite(i, spacing, t * 0.13);

        // The applied field is strongest at the centre, so outer peaks are shorter and the crown
        // domes. Bass plays the part of field strength: quiet leaves the surface nearly flat and
        // loud drives the peaks up, which is exactly how the real instability responds.
        float ringFall = 1.0 - clamp(length(site.xz) / (spacing * 2.4), 0.0, 1.0) * 0.55;
        float field = bass + transient * 0.35;
        // A ripple travelling outward from the centre, so the array breathes in sequence rather
        // than every peak pumping in unison.
        float ripple = 0.5 + 0.5 * sin(t * 1.1 - length(site.xz) * 5.0);
        float h = max((0.10 + field * 0.62 + ripple * 0.05) * ringFall, 0.02);

        float3 q = p - float3(site.x, -0.42, site.z);
        return sdRoundCone(q, 0.135 * ringFall, 0.018, h);
    }

    float sceneDist(float3 p, float t) {
        // The shallow pool the peaks rise out of. Dividing by the y-scale keeps the result a
        // conservative *under*-estimate of the true distance: a squashed sphere written the naive
        // way overestimates by the scale factor, and an overestimating SDF lets the march step
        // straight through the surface it was meant to stop at.
        float pool = (length(float3(p.x, (p.y + 0.62) * 3.2, p.z)) - 0.80) / 3.2;
        float d = pool;
        for (int i = 0; i < SPIKES; i++) {
            if (i >= activeSpikes) {
                break;
            }
            // Tight blend radius. Real peaks meet the pool in a cusp; a wide smin would melt the
            // feet together into one mound and undo the cone shape above.
            d = sminCubic(d, spikeDist(p, i, t), 0.10);
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

        // Raised and tilted down onto the pool. The old near-horizontal camera was fine for a ring
        // of blobs, but a hexagonal array is only legible as one if you can see across it.
        float3 rayOrigin = float3(0.0, 0.85, -2.2);
        float3 rayDir = normalize(float3(uv.x, -uv.y - 0.55, 1.0));

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
        float3 viewDir = normalize(rayOrigin - hitPos);

        // Ferrofluid is magnetite suspended in oil: for practical purposes it is opaque black.
        // Essentially nothing you see on real footage of it is diffuse colour - it is all
        // reflection. So the diffuse term here is a trace, and the surface is instead sold by two
        // sharp highlights, a strong Fresnel edge, and a cheap environment reflection.
        float3 keyDir = normalize(float3(0.5, 0.8, -0.6));
        float3 fillDir = normalize(float3(-0.6, 0.35, -0.4));

        float keySpec = pow(max(dot(normal, normalize(keyDir + viewDir)), 0.0), 150.0);
        float fillSpec = pow(max(dot(normal, normalize(fillDir + viewDir)), 0.0), 40.0);
        float diffuse = max(dot(normal, keyDir), 0.0);

        // Schlick Fresnel with a dielectric F0. A near-black dielectric is almost mirror-like at
        // grazing angles, which is why the silhouette and the flanks of the peaks in real footage
        // are so much brighter than the surfaces facing you.
        float cosTheta = clamp(dot(normal, viewDir), 0.0, 1.0);
        float fresnel = 0.04 + 0.96 * pow(1.0 - cosTheta, 5.0);

        // A stand-in environment: reflect the view vector and read a vertical gradient, bright
        // above and dark below. Two lines, no texture, and it does more for "this is a liquid
        // mirror" than any amount of extra lighting would - because the thing that actually makes
        // a mirror look like a mirror is having something to reflect.
        float3 reflDir = reflect(-viewDir, normal);
        float envUp = clamp(reflDir.y * 0.5 + 0.5, 0.0, 1.0);
        float3 env = mix(baseColor * 0.15, rimColor, envUp * envUp);

        // Real ferrofluid is black, and a physically honest version of this was black too - which
        // left it with nothing to contrast against a dark UI, since a black shape on a dark
        // background is just a hole. So the body is deliberately tinted with the theme's base
        // colour and given a real diffuse term: a *coloured* liquid metal rather than a literal
        // one. The specular model above is untouched, and that is what still reads as ferrofluid -
        // the tight highlights and the Fresnel-bright flanks do the identifying, not the fill.
        float3 col = baseColor * 0.18
            + baseColor * diffuse * 0.45
            + env * fresnel * 1.4
            + highlightColor * keySpec * 1.5
            + highlightColor * fillSpec * 0.30
            + rimColor * treble * 0.05;

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

    // The march is re-run for every pixel every time elapsedSeconds changes, and the Canvas below
    // reads it - so the rate this advances at *is* the render frame rate. Left uncapped it followed
    // the display, meaning a 120Hz phone paid twice the GPU of a 60Hz one to animate a slow,
    // heavily-blurred blob that reads no differently either way. Frames arriving sooner than the
    // tier's interval are dropped without touching the state, so no redraw happens for them.
    //
    // Wall-clock time is still read straight from the vsync stamp rather than accumulated from the
    // frames we kept, so dropping frames slows the render rate without slowing the animation.
    val frameIntervalNanos = 1_000_000_000L / quality.targetFps
    LaunchedEffect(isActive, frameIntervalNanos) {
        if (!isActive) return@LaunchedEffect
        var startNanos = 0L
        var lastEmitNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (startNanos == 0L) {
                    startNanos = nanos
                    lastEmitNanos = nanos
                } else if (nanos - lastEmitNanos >= frameIntervalNanos) {
                    lastEmitNanos = nanos
                    elapsedSeconds = (nanos - startNanos) / 1_000_000_000f
                }
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
        shader.setIntUniform("activeSpikes", quality.activeSpikes)
        shader.setFloatUniform("highlightColor", currentHighlight.red, currentHighlight.green, currentHighlight.blue)
        shader.setFloatUniform("rimColor", currentRim.red, currentRim.green, currentRim.blue)
        shader.setFloatUniform("baseColor", currentBase.red, currentBase.green, currentBase.blue)
        drawRect(brush = ShaderBrush(shader), size = Size(size.width, size.height))
    }
}
