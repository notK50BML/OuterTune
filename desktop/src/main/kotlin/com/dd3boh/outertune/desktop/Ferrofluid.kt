/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A pool of ferrofluid that the music magnetises.
 *
 * The phone's version is a cluster of droplets drifting apart and together, which reads as
 * something pulsing. This one is built on what ferrofluid actually does over a magnet, which is
 * what the footage people know is of:
 *
 * - **It stays flat until it cannot.** Below a critical field strength the pool is a smooth dome;
 *   past it, the surface becomes unstable and erupts into spikes on a hexagonal lattice (the
 *   Rosensweig instability). Here the bass is the magnet, so a quiet passage is a calm pool and a
 *   beat throws up a crown of spikes - rather than everything scaling up and down together.
 * - **It has mass.** The field the shader sees is not the bass level but a spring driven by it -
 *   under-damped, so spikes overshoot on a hit, ring, and settle, and collapse back with a little
 *   slosh when the bass drops away. That inertia is most of what separates a liquid from a graph.
 * - **It is a black mirror.** Magnetite in oil is opaque, so almost everything seen is reflection:
 *   a studio of soft lights - the key light white, the rim light in the theme's colour - mirrored
 *   in the spikes' flanks, Fresnel-bright at grazing angles, with dark valleys between the spikes
 *   and the pool sitting on a glossy plate that grounds it.
 * - **Treble shivers it**: a fine ripple over the surface and a tremble in the spike tips, the way
 *   real spikes vibrate in a changing field. The whole lattice leans slowly as the field drifts.
 *
 * Raymarched as a height field in one SkSL shader, at a fraction of the window's resolution and
 * scaled up - it is a background, and the soft edge that costs nothing is the right look for it.
 */
private const val FERROFLUID_SKSL = """
    uniform float2 resolution;
    uniform float time;
    uniform float field;      // the magnet: spring-filtered bass, 0..~1.3
    uniform float shimmer;    // treble
    uniform float mid;        // the mids - each spike answers them on its own
    uniform float2 lean;      // where the field pulls the spikes, in pool units
    uniform float3 keyColor;  // the white-ish key light
    uniform float3 rimColor;  // the theme's colour, for the rim light
    uniform float3 baseColor; // the theme's dark, for the environment

    const float R = 1.0;          // pool radius
    const float S = 0.20;         // spike spacing
    const float TOP = 0.95;       // nothing rises above this
    const float PLATE = 1.45;     // the plate the pool sits on
    const int STEPS = 150;

    float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }

    // The nearest point of a hexagonal lattice with spacing S: the nearer of two offset grids.
    float2 hexCentre(float2 p) {
        float2 r = float2(S, S * 1.7320508);
        float2 h = r * 0.5;
        float2 a = mod(p + h, r) - h;
        float2 b = mod(p, r) - h;
        return dot(a, a) < dot(b, b) ? p - a : p - b;
    }

    // The resting pool, raised a little in the middle by the field.
    float dome(float r) {
        float x = clamp(r / R, 0.0, 1.0);
        return 0.085 * sqrt(max(1.0 - x * x, 0.0)) + field * 0.07 * exp(-r * r * 3.2);
    }

    // Spike height above the dome at p.
    float spikes(float2 p) {
        float2 c = hexCentre(p);
        float rc = length(c);
        // The instability: nothing below the critical field, then a sharp onset.
        float onset = smoothstep(0.08, 0.55, field);
        float envelope = smoothstep(R * 0.93, R * 0.18, rc);
        float h = hash(c);
        // About as tall as they are apart at full field, as real spikes are; taller in the middle.
        // Every spike also answers the mids on its own phase, so the crown dances spike by spike
        // rather than rising and falling as one piece.
        float h2 = hash(c + float2(7.13, 3.71));
        float dance = 0.8 + mid * (0.9 * h2 + 0.4 * sin(time * (5.0 + 6.0 * h2) + h2 * 30.0));
        float amp = 0.34 * onset * envelope * (0.75 + 0.5 * h) * (0.8 + 0.3 * field) * dance;
        // Tips lean with the field and tremble with the treble; the base stays put, so the spike
        // bends rather than sliding about.
        float2 tremble = shimmer * 0.018 * float2(sin(time * 23.0 + h * 40.0), cos(time * 19.0 + h * 31.0));
        float2 tip = (lean + tremble) * (0.35 + 0.65 * envelope) * 0.6;
        // Inside half the spacing, so a spike never reaches a neighbour's cell and gets sliced.
        float w = S * 0.46;
        float d0 = length(p - c);
        float t0 = clamp(1.0 - d0 / w, 0.0, 1.0);
        // A rounded apex rather than a needle: the distance is eased near the centre, which gives
        // the tip a small radius - still a cusp from a distance, just not a pin.
        float dr = length(p - c - tip * t0 * t0);
        float d = sqrt(dr * dr + 0.0009) - 0.03;
        float t = clamp(1.0 - d / w, 0.0, 1.0);
        // Concave flanks to a sharp tip - the cusp shape of a real ferrofluid spike.
        return amp * pow(t, 2.3);
    }

    // The surface height, or -1 outside the pool.
    float heightAt(float2 p) {
        float r = length(p);
        if (r > R) return -1.0;
        float edge = smoothstep(R, R * 0.86, r);
        float ripple = shimmer * 0.006 * sin(r * 46.0 - time * 17.0) * edge;
        return dome(r) + spikes(p) * edge + ripple;
    }

    // A photographer's studio, seen in a mirror: a dark room, a big white softbox above and in
    // front, a rim light behind in the theme's colour, and a thin strip light low on the left.
    float3 environment(float3 d) {
        // Lit from behind and above, where a pool seen from the front reflects it: the calm surface
        // then shows a long soft sheen instead of reading as a hole.
        float behind = smoothstep(-0.2, 0.6, d.z);
        float3 col = mix(baseColor * 0.04, baseColor * 0.30 + keyColor * 0.04, smoothstep(-0.1, 0.9, d.y) * behind);
        float3 k = normalize(float3(0.22, 0.62, 0.75));
        float key = smoothstep(0.86, 0.975, dot(d, k));
        float3 rim = normalize(float3(-0.78, 0.30, 0.55));
        float rimL = smoothstep(0.90, 0.985, dot(d, rim));
        // A thin horizon of light behind the pool, caught as a line along every spike's flank.
        float strip = smoothstep(0.035, 0.0, abs(d.y - 0.10)) * smoothstep(0.15, 0.6, d.z);
        float3 fill = normalize(float3(0.82, 0.38, 0.42));
        float fillL = smoothstep(0.82, 0.99, dot(d, fill));
        return col + keyColor * key * 4.0 + rimColor * rimL * 2.6 + keyColor * strip * 0.9 + rimColor * fillL * 0.8;
    }

    half4 main(float2 fragCoord) {
        float2 uv = (fragCoord - 0.5 * resolution) / resolution.y;
        uv.y = -uv.y;
        float3 ro = float3(0.0, 1.05, -2.45);
        float3 target = float3(0.0, 0.10, 0.15);
        float3 fw = normalize(target - ro);
        float3 rt = normalize(cross(float3(0.0, 1.0, 0.0), fw));
        float3 up = cross(fw, rt);
        float3 rd = normalize(fw * 1.55 + rt * uv.x + up * uv.y);
        if (rd.y >= -0.001) return half4(0.0);

        // March only where the pool can be: between the top of everything and the plate, and
        // inside the pool's cylinder - solved in closed form, so a ray that never passes over the
        // pool costs nothing and goes straight to the plate.
        float t = max((TOP - ro.y) / rd.y, 0.0);
        float tEnd = (0.0 - ro.y) / rd.y;
        float2 o2 = ro.xz;
        float2 d2 = rd.xz;
        float qa = dot(d2, d2);
        float qb = dot(o2, d2);
        float qc = dot(o2, o2) - R * R;
        float disc = qb * qb - qa * qc;
        float tCylIn = 1e9;
        float tCylOut = -1e9;
        if (disc > 0.0) {
            float sq = sqrt(disc);
            tCylIn = (-qb - sq) / qa;
            tCylOut = (-qb + sq) / qa;
        }
        float tMarchEnd = min(tEnd, tCylOut);
        t = max(t, tCylIn);
        float tPrev = t;
        bool hit = false;
        // Spikes are far steeper than the pool, and a step sized for the pool jumps straight over a
        // thin tip - which showed as specks floating above the crown. The stronger the field, the
        // finer the march.
        float stepScale = mix(0.38, 0.17, smoothstep(0.16, 0.75, field));
        float3 p = ro;
        for (int i = 0; i < STEPS; i++) {
            if (t > tMarchEnd) break;
            p = ro + rd * t;
            float h = heightAt(p.xz);
            float dh = p.y - (h < 0.0 ? -0.05 : h);
            if (dh < 0.0012) { hit = h >= 0.0; break; }
            tPrev = t;
            // Spikes are steep, so the step is a fraction of the height above the surface.
            t += max(dh * stepScale, 0.0015);
        }

        if (hit) {
            // Refine the crossing so spike edges stay crisp.
            float lo = tPrev;
            float hi = t;
            for (int j = 0; j < 6; j++) {
                float mid = 0.5 * (lo + hi);
                float3 q = ro + rd * mid;
                if (q.y - heightAt(q.xz) > 0.0) lo = mid; else hi = mid;
            }
            p = ro + rd * hi;
            float2 xz = p.xz;
            float e = 0.0022;
            float hx = heightAt(xz + float2(e, 0.0)) - heightAt(xz - float2(e, 0.0));
            float hz = heightAt(xz + float2(0.0, e)) - heightAt(xz - float2(0.0, e));
            float3 n = normalize(float3(-hx, 2.0 * e, -hz));
            float3 v = -rd;
            float3 refl = reflect(rd, n);
            float fresnel = 0.05 + 0.95 * pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 5.0);
            float r = length(xz);
            // Valleys between spikes see less of the studio.
            float rise = p.y - dome(r);
            float ao = mix(0.42, 1.0, smoothstep(-0.005, 0.16, rise));
            float3 col = environment(refl) * fresnel * ao + baseColor * 0.015;
            col = col / (1.0 + col);
            col = sqrt(col);
            float alpha = smoothstep(R, R - 0.012, r);
            return half4(half3(col) * alpha, alpha);
        }

        // The plate: a dark gloss that reflects the room faintly, with a soft shadow under the pool.
        float tp = tEnd;
        float3 q = ro + rd * tp;
        float rp = length(q.xz);
        if (rp > PLATE) return half4(0.0);
        float3 refl = reflect(rd, float3(0.0, 1.0, 0.0));
        float fresnel = 0.04 + 0.96 * pow(1.0 - clamp(-rd.y, 0.0, 1.0), 5.0);
        float shadow = mix(0.25, 1.0, smoothstep(R * 0.98, R * 1.25, rp));
        float3 col = environment(refl) * fresnel * 0.16 * shadow + baseColor * 0.03;
        col = col / (1.0 + col);
        col = sqrt(col);
        float alpha = smoothstep(PLATE, PLATE - 0.25, rp) * 0.92;
        return half4(half3(col) * alpha, alpha);
    }
"""

/** One frame's worth of what the shader is told. */
internal data class FerrofluidFrame(
    val time: Float = 0f,
    val field: Float = 0f,
    val shimmer: Float = 0f,
    val leanX: Float = 0f,
    val leanZ: Float = 0f,
    val mid: Float = 0f,
)

private val effect: RuntimeEffect? by lazy { runCatching { RuntimeEffect.makeForShader(FERROFLUID_SKSL) }.getOrNull() }

/** Whether the shader compiled here. If not, the ferrofluid background falls back to the gradient. */
val ferrofluidAvailable: Boolean get() = effect != null

/**
 * The ferrofluid, driven by the audible spectrum.
 *
 * @param renderScale how much of the real resolution the march runs at. Most of the cost is per
 *   pixel; a soft background loses nothing visible at three quarters.
 */
@Composable
fun FerrofluidVisualiser(
    tap: VisualizerTap,
    playedFrames: () -> Long,
    playing: Boolean,
    keyColor: Color,
    rimColor: Color,
    baseColor: Color,
    modifier: Modifier = Modifier,
    renderScale: Float = 0.6f,
    /** How hard the music drives it - see [FerroReactivity]. */
    reactivity: Float = 1f,
) {
    var frame by remember { mutableStateOf(FerrofluidFrame()) }
    val currentReactivity by rememberUpdatedState(reactivity)
    val currentPlaying by rememberUpdatedState(playing)

    LaunchedEffect(tap) {
        var last = 0L
        var time = 0f
        var field = 0f
        var velocity = 0f
        var bassAvg = 0f
        var shimmer = 0f
        var mid = 0f
        var lastDrawn = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                // The simulation runs every frame; the picture is only redrawn about forty times a
                // second, and not at all once the pool has come to rest with nothing playing - the
                // per-pixel march is the whole cost, and a still pool needs no new frames.
                val settled = !currentPlaying && field < 0.002f && kotlin.math.abs(velocity) < 0.002f && shimmer < 0.002f && mid < 0.002f
                time += dt
                val levels = if (currentPlaying) tap.sample(playedFrames()) else FloatArray(0)
                val bass = if (levels.size >= 4) (levels[0] + levels[1] + levels[2] + levels[3]) / 4f else 0f
                val treble = if (levels.size >= 20) levels.copyOfRange(levels.size - 10, levels.size).average().toFloat() else 0f
                // A kick: bass rising faster than its own recent average gives the spring a shove.
                val rise = (bass - bassAvg).coerceAtLeast(0f)
                bassAvg += (bass - bassAvg) * (1f - exp(-dt * 6f))
                // The magnet's field: an under-damped spring pulled toward the bass - it overshoots a
                // hit, rings, and settles; with the music off it slumps back flat.
                val r = currentReactivity
                // Square-rooted, so a quiet passage still moves it and a loud one does not just
                // pin it at the top. A stiffer, lightly damped spring: hits snap up and ring.
                val target = (kotlin.math.sqrt(bass) * 1.3f * r).coerceIn(0f, 1.3f)
                val stiffness = 130f
                val damping = 10f
                velocity += (stiffness * (target - field) - damping * velocity) * dt + rise * 16f * r
                val midLevel = if (levels.size >= 16) levels.copyOfRange(6, 16).average().toFloat() else 0f
                mid += ((midLevel * 1.4f * r).coerceIn(0f, 1.5f) - mid) * (1f - exp(-dt * 14f))
                field = (field + velocity * dt).coerceIn(0f, 1.35f)
                shimmer += (treble * 1.6f - shimmer) * (1f - exp(-dt * 12f))
                // The field drifts, so the spikes lean slowly one way and then another.
                val leanX = 0.035f * sin(time * 0.31f) + 0.02f * sin(time * 0.83f + 1.3f)
                val leanZ = 0.025f * cos(time * 0.27f + 0.7f)
                if (!settled && now - lastDrawn >= 24_000_000L) {
                    lastDrawn = now
                    frame = FerrofluidFrame(time, field, shimmer, leanX, leanZ, mid)
                }
            }
        }
    }

    FerrofluidScene(frame, keyColor, rimColor, baseColor, modifier, renderScale)
}

/** The shader alone, for a given frame - what [FerrofluidVisualiser] draws, and what a test can. */
@Composable
internal fun FerrofluidScene(
    frame: FerrofluidFrame,
    keyColor: Color,
    rimColor: Color,
    baseColor: Color,
    modifier: Modifier = Modifier,
    renderScale: Float = 0.6f,
) {
    val fx = effect ?: return
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (renderScale >= 1f) Modifier else Modifier.layout { measurable, constraints ->
                    val w = (constraints.maxWidth * renderScale).roundToInt().coerceAtLeast(1)
                    val h = (constraints.maxHeight * renderScale).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.placeWithLayer(0, 0) {
                            scaleX = 1f / renderScale
                            scaleY = 1f / renderScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                    }
                }
            ),
    ) {
        val builder = RuntimeShaderBuilder(fx)
        builder.uniform("resolution", size.width, size.height)
        builder.uniform("time", frame.time)
        builder.uniform("field", frame.field)
        builder.uniform("shimmer", frame.shimmer)
        builder.uniform("mid", frame.mid)
        builder.uniform("lean", frame.leanX, frame.leanZ)
        builder.uniform("keyColor", keyColor.red, keyColor.green, keyColor.blue)
        builder.uniform("rimColor", rimColor.red, rimColor.green, rimColor.blue)
        builder.uniform("baseColor", baseColor.red, baseColor.green, baseColor.blue)
        drawRect(brush = ShaderBrush(builder.makeShader()))
    }
}

/** How hard the music drives the ferrofluid - the phone's ferrofluid reactivity tiers. */
enum class FerroReactivity(val label: String, val multiplier: Float) {
    Subtle("Subtle", 0.6f),
    Normal("Normal", 1f),
    High("High", 1.5f),
    Extreme("Extreme", 2.1f),
}
