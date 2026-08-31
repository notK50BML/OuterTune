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
 * trimming step or droplet counts - and a softened, background-only crown loses little to the
 * resulting blur; it already reads as "underwater". [HIGH] exists because this effect is most at
 * home on a desktop-class GPU (a WSA build on a PC), where the pixel budget the phone tiers are
 * protecting simply isn't the constraint.
 */
enum class FerrofluidQuality(
    val renderScale: Float,
    val maxSteps: Int,
    val targetFps: Int,
    /** How many droplets are marched - see `activeBlobs` in [FERROFLUID_GPU_SHADER_SRC]. */
    val activeBlobs: Int,
) {
    /** Cheapest: few droplets, short march, low resolution. For phones on battery. */
    LOW(0.32f, 18, 24, 5),

    /** The default. Deliberately frugal - this is a background, not the subject. */
    MEDIUM(0.42f, 24, 24, 6),

    /** Full resolution and more droplets, for a desktop-class GPU. */
    HIGH(1.0f, 40, 60, 9),

    /** Everything uncapped. Genuinely expensive; for a discrete GPU or a flagship Xclipse/Adreno. */
    ULTRA(1.0f, 56, 60, 9),
}

/**
 * How hard the audio drives the shape, as a multiplier on the bass/transient terms in
 * [FERROFLUID_GPU_SHADER_SRC].
 *
 * Separate from [FerrofluidQuality] because it is a taste question rather than a cost one - it
 * changes nothing about how expensive a frame is to draw, only how far the droplets travel for a
 * given amount of music. [SUBTLE] is roughly where this effect sat before, for anyone who wants a
 * background that stays in the background.
 */
enum class FerrofluidReactivity(val multiplier: Float) {
    SUBTLE(0.5f),
    NORMAL(1f),
    HIGH(1.5f),
    EXTREME(2f),
}

/**
 * Experimental, opt-in alternative to [LiquidShapeStyle.FERROFLUID]'s Canvas polygon: a genuine
 * raymarched scene running as a single AGSL fragment shader rather than a flat 2D shape.
 *
 * The behaviour it models is the free-floating kind: a mass that breaks up into droplets and flows
 * back together, rather than a static field's spike lattice (the Rosensweig instability, which was
 * tried here and looks nothing like the footage people mean by "ferrofluid").
 *
 * - **Break-up and remerge is emergent, not animated.** Every droplet shares one slow breathing
 *   cycle that pushes it away from a central core and draws it back. The smin blend radius is
 *   fixed, so that one number decides whether neighbours are within blend range: close together
 *   they fuse into a single connected mass, far apart they separate into distinct beads with necks
 *   stretching and snapping between them. Nothing sequences the split - it falls out of the blend.
 *   See `blobDist`.
 * - **The material is mostly specular, but deliberately tinted.** Real ferrofluid is magnetite in
 *   oil, so optically it is opaque black and nearly everything visible in footage is reflection.
 *   A physically honest version of that is a black shape on a dark UI - which is just a hole - so
 *   the body carries a real wrapped-diffuse term in the theme's colour, and the sharp highlight,
 *   Schlick Fresnel edge and reflected gradient do the work of identifying the material.
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
 * blending `activeBlobs` distance fields, plus four more for the normal at the hit point
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
    uniform int activeBlobs;
    /** How hard the audio drives the shape - 1.0 is the default tier. */
    uniform float reactivity;
    // Material 3 roles, linear-ish sRGB components. highlightColor carries the specular glint and
    // rimColor the Fresnel edge, so the crown reads as part of the current theme rather than a
    // fixed cold grey.
    uniform float3 highlightColor;
    uniform float3 rimColor;
    uniform float3 baseColor;

    // The droplet ceiling. The tier's activeBlobs decides how many are actually marched (early
    // break in sceneDist), so a cheap tier renders fewer beads rather than a degraded version of
    // all nine.
    const int BLOBS = 9;
    // The ceiling, and a compile-time constant on purpose: AGSL follows GLSL ES 2.0 rules, where a
    // loop's bound has to be a constant expression, so the quality tier cannot be the bound itself.
    // It's applied as an early break inside the loop instead - a dynamic break is allowed, which
    // the surface-hit test below already relies on.
    const int MAX_STEPS = 56;
    const float MAX_DIST = 6.0;
    const float SURF_DIST = 0.008;
    // Everything the scene can ever occupy fits in this sphere about the origin: a droplet at full
    // spread sits ~0.89 out with a radius up to 0.25, plus vertical drift - so ~1.16 at worst.
    const float BOUND_RADIUS = 1.40;

    float sminCubic(float a, float b, float k) {
        float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
        return mix(b, a, h) - k * h * (1.0 - h);
    }

    // One droplet. The whole effect is carried by `spread`: every droplet shares one slow
    // breathing cycle that pushes it out from the centre and draws it back, and because the blend
    // radius in sceneDist is fixed, that single number decides whether neighbours are inside each
    // other's blend range or not. Small spread and the smin fuses them into one connected mass;
    // large spread and they pull away into separate beads, with necks stretching and snapping
    // between them on the way out. The break-up-and-remerge cycle falls out of the blend rather
    // than being animated by hand.
    float blobDist(float3 p, int i, float t) {
        float fi = float(i);

        // Golden-angle phase, so the droplets never settle into an obvious wheel.
        float orbit = fi * 2.3999632 + t * 0.17 + sin(t * 0.28 + fi) * 0.35;

        // The slow cycle is now only an idle drift - enough that a paused or quiet track still
        // moves. What actually drives the break-up is the audio. The music used to be a 45% nudge
        // on top of a cycle that swung four times as far, so the shape was really just breathing on
        // a timer with a faint wobble; now the timer is the small term and the track is the large
        // one, and the mass visibly flies apart on a loud passage and gathers back in a quiet one.
        float cycle = 0.5 + 0.5 * sin(t * 0.24 + fi * 0.22);
        float ambient = 0.12 + 0.10 * cycle;
        // Clamped so that turning reactivity up raises how hard normal material hits rather than
        // letting a loud peak throw droplets outside the bounding sphere.
        float drive = clamp((bass * 1.1 + transient * 0.6) * reactivity, 0.0, 1.6);
        float spread = ambient + 0.42 * drive;

        // Droplets thin as they stretch apart - surface tension pulling a separating bead back
        // towards a sphere - and swell on a transient, so a beat reads as a pulse as well as a
        // push outward.
        float bob = sin(t * 0.63 + fi * 1.9) * 0.16;
        float3 centre = float3(cos(orbit) * spread, bob, sin(orbit) * spread);
        float radius = 0.19 - 0.04 * clamp(drive, 0.0, 1.0) + transient * 0.05 * reactivity;
        return length(p - centre) - radius;
    }

    float sceneDist(float3 p, float t) {
        // A core the droplets flow out of and back into, so a body is always present and the scene
        // never empties out at full spread.
        float core = length(p * float3(1.0, 1.35, 1.0)) - (0.17 + bass * 0.10 * reactivity);
        float d = core;
        for (int i = 0; i < BLOBS; i++) {
            if (i >= activeBlobs) {
                break;
            }
            // Generous blend radius. This is what makes droplets reach for each other and join
            // with a neck, rather than intersecting like two hard spheres.
            d = sminCubic(d, blobDist(p, i, t), 0.26);
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

        // Centred on the mass. The tilted-down view a flat pool needed just crops a floating
        // droplet cluster awkwardly.
        float3 rayOrigin = float3(0.0, 0.20, -2.2);
        float3 rayDir = normalize(float3(uv.x, -uv.y - 0.06, 1.0));

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

        float keySpec = pow(max(dot(normal, normalize(keyDir + viewDir)), 0.0), 110.0);
        // Wrapped diffuse: light bleeds a little past the terminator, which keeps the far side of
        // a droplet from going flat black and lets the theme colour read across the whole mass
        // instead of only the lit cap.
        float diffuse = max(dot(normal, keyDir) * 0.5 + 0.5, 0.0);
        float fill = max(dot(normal, fillDir) * 0.5 + 0.5, 0.0);

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
        float3 col = baseColor * 0.34
            + baseColor * diffuse * 0.85
            + rimColor * fill * 0.22
            + env * fresnel * 0.9
            + highlightColor * keySpec * 1.3
            + rimColor * treble * 0.18 * reactivity;

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
    reactivity: FerrofluidReactivity = FerrofluidReactivity.NORMAL,
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
        // Where the clock was when this effect (re)started. Without it, pausing and resuming - or
        // changing quality, which restarts the effect too - would take the scene back to t=0 and
        // snap every droplet to a different place mid-view.
        var baseSeconds = elapsedSeconds
        while (true) {
            withFrameNanos { nanos ->
                if (startNanos == 0L) {
                    startNanos = nanos
                    lastEmitNanos = nanos
                    baseSeconds = elapsedSeconds
                } else if (nanos - lastEmitNanos >= frameIntervalNanos) {
                    lastEmitNanos = nanos
                    elapsedSeconds = baseSeconds + (nanos - startNanos) / 1_000_000_000f
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
        shader.setIntUniform("activeBlobs", quality.activeBlobs)
        shader.setFloatUniform("reactivity", reactivity.multiplier)
        shader.setFloatUniform("highlightColor", currentHighlight.red, currentHighlight.green, currentHighlight.blue)
        shader.setFloatUniform("rimColor", currentRim.red, currentRim.green, currentRim.blue)
        shader.setFloatUniform("baseColor", currentBase.red, currentBase.green, currentBase.blue)
        drawRect(brush = ShaderBrush(shader), size = Size(size.width, size.height))
    }
}
