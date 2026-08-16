/*
 * Copyright (C) 2026 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

private const val TAG = "LiquidChromaticShock"

/**
 * AGSL (Android Graphics Shading Language). A real per-pixel GPU shader, not a Canvas draw call -
 * this is what "GPU-light WebGL-shader-style" actually means on Android: RuntimeShader compiles
 * this text to a Skia pipeline shader at runtime and runs it entirely on the GPU, the same
 * category of work a WebGL fragment shader does, just through Android's own graphics stack
 * instead of a browser's. There is no bundled engine and nothing added to the APK beyond this
 * string - AGSL is a framework API (API 33+), not a library.
 *
 * What it does, matching the brief: a "kick" (a transient/beat) launches a ring outward from the
 * shape's centre. The ring is a *refraction*, not a colour overlay - pixels near it are sampled
 * from further along the radial direction they sit on, as if light bent around a real pressure
 * front, and each colour channel is offset by a different amount (chromatic aberration), which is
 * what "splitting and dragging colour" turns into in shader terms. The ring starts broad and soft
 * and narrows as it travels outward ("compressing"), then the whole thing fades. "composable" is
 * whatever was drawn before this shader ran - the existing Petal/Spheres/Ferrofluid Canvas
 * animation, which is already continuously moving on its own ("a living ... surface") - not a
 * separate multi-frame accumulation buffer. A true video-feedback loop (each frame sampling the
 * *previous* frame's own rendered output, not just this frame's content) is a materially bigger,
 * riskier piece of plumbing (an offscreen ping-pong render target) that isn't attempted here.
 */
private const val CHROMATIC_SHOCK_SHADER_SRC = """
    uniform shader composable;
    uniform float2 resolution;
    uniform float shockAge;
    uniform float shockStrength;

    half4 main(float2 fragCoord) {
        if (shockAge > 1.4 || shockStrength <= 0.001) {
            return composable.eval(fragCoord);
        }

        float2 center = resolution * float2(0.5, 0.42);
        float2 delta = fragCoord - center;
        float dist = length(delta);
        float2 dir = dist > 0.5 ? delta / dist : float2(0.0, 0.0);

        float minDim = min(resolution.x, resolution.y);
        float speed = minDim * 1.15;
        float front = shockAge * speed;
        float bandWidth = max(minDim * 0.16 - shockAge * minDim * 0.11, minDim * 0.012);
        float diff = dist - front;
        float band = exp(-(diff * diff) / (bandWidth * bandWidth));

        float life = clamp(1.0 - shockAge / 1.4, 0.0, 1.0);
        float amount = band * shockStrength * life;

        float push = amount * minDim * 0.045;
        half4 rSample = composable.eval(fragCoord + dir * (push * 1.35));
        half4 gSample = composable.eval(fragCoord + dir * push);
        half4 bSample = composable.eval(fragCoord + dir * (push * 0.65));

        half4 col = half4(rSample.r, gSample.g, bSample.b, gSample.a);
        col.rgb += half3(amount * 0.55);
        return col;
    }
"""

/** How large a [com.dd3boh.outertune.audio.VisualizerFrame.transient] spike has to be to count as
 *  a fresh "kick" worth launching a wavefront for. */
private const val KICK_TRIGGER_THRESHOLD = 0.45f

/** Shortest gap between two kicks - without this, a sustained loud passage would relaunch a new
 *  wavefront on every single frame the transient stayed above the threshold, never letting one
 *  finish before the next started. */
private const val MIN_KICK_INTERVAL_SECONDS = 0.35f

/**
 * Wraps [content] with the chromatic-shock ripple, or passes it through untouched when the effect
 * can't run: below Android 13 (RuntimeShader doesn't exist), when [enabled] is false, or if this
 * device's Skia build rejects the AGSL source for any reason - a hand-written shader failing to
 * compile should never be the reason the rest of the player breaks, so that failure is caught once
 * (not per-frame) and treated the same as "not supported".
 */
@Composable
fun ChromaticShockEffect(
    enabled: Boolean,
    isActive: Boolean,
    transient: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Box(modifier) { content() }
        return
    }

    val shader = remember {
        runCatching { RuntimeShader(CHROMATIC_SHOCK_SHADER_SRC) }
            .onFailure { Log.e(TAG, "AGSL shader failed to compile, disabling chromatic shock", it) }
            .getOrNull()
    }
    if (shader == null) {
        Box(modifier) { content() }
        return
    }

    var elapsedSeconds by remember { mutableFloatStateOf(0f) }
    var lastKickStartSeconds by remember { mutableFloatStateOf(-999f) }
    var kickStrength by remember { mutableFloatStateOf(0f) }
    val currentTransient by rememberUpdatedState(transient)

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    val deltaSeconds = (nanos - lastFrameNanos) / 1_000_000_000f
                    elapsedSeconds += deltaSeconds
                    if (currentTransient > KICK_TRIGGER_THRESHOLD &&
                        elapsedSeconds - lastKickStartSeconds > MIN_KICK_INTERVAL_SECONDS
                    ) {
                        lastKickStartSeconds = elapsedSeconds
                        kickStrength = currentTransient.coerceIn(0f, 1f)
                    }
                }
                lastFrameNanos = nanos
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            // Reading these mutable-state values here, in the layer's own draw-invalidation
            // block, is what makes the layer redraw every frame the effect is live - the same
            // mechanism LiquidBackground's own drawBehind blocks already rely on for flowTime.
            val age = elapsedSeconds - lastKickStartSeconds
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("shockAge", age)
            shader.setFloatUniform("shockStrength", kickStrength)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "composable")
                .asComposeRenderEffect()
        }
    ) {
        content()
    }
}
