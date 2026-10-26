/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.util.Collections

/**
 * Album art, fetched once per URL and then kept.
 *
 * A cache is not an optimisation here so much as a correctness matter for the UI: a lazy list
 * recycles rows constantly, so without one every scroll would re-request the same handful of images
 * and the list would flicker as they reloaded. Held in a plain synchronized map because the whole
 * point is that it is small and shared - there are only ever as many entries as there are distinct
 * covers on screen and recently played.
 *
  * Bounded, and the bound matters more than it looks. A cover decodes to roughly 1.2MB of RGBA, so
 * the first version of this - capped at 300 - was quietly authorising 350MB of bitmaps, which is a
 * large part of why the app measured heavier than a music player should. Sixty is comfortably more
 * than can be on screen at once plus a full recently-played shelf, and about 70MB at worst.
 *
 * Cleared wholesale at the cap rather than evicting cleverly: the cost of being wrong is one reload
 * of a visible image, which is not worth an LRU.
 */
internal object ArtworkCache {
    private const val MAX_ENTRIES = 120

    /**
     * Decoded pixels kept, in bytes. A count alone let sixty 1080px player covers - near 5MB each
     * once decoded - hold close to 300MB outside the Java heap, where nothing else limits it.
     */
    private const val MAX_PIXEL_BYTES = 64L * 1024 * 1024
    private val ImageBitmap.pixelBytes: Long get() = width.toLong() * height * 4
    // Access-ordered, so the one dropped at the cap is the least recently drawn, not the oldest
    // loaded - a cover on screen the whole time is never the one thrown away.
    private val images = Collections.synchronizedMap(LinkedHashMap<String, ImageBitmap>(16, 0.75f, true))

    /**
     * Covers on disk, one file per picture holding the largest copy seen - so a cover that has been
     * shown once is there offline, which is what a downloaded song needs, and an app start does not
     * fetch every cover again. Pruned to [DISK_LIMIT_BYTES], oldest first.
     */
    private val diskDir by lazy { java.io.File(defaultDataDirectory(), "artwork").apply { mkdirs() } }

    private fun diskFile(url: String): java.io.File {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(url.sizeKey().toByteArray())
        return java.io.File(diskDir, digest.joinToString("") { "%02x".format(it) } + ".img")
    }

    private fun readDisk(url: String): ImageBitmap? = runCatching {
        diskFile(url).takeIf { it.isFile }?.readBytes()?.let { Image.makeFromEncoded(it).toComposeImageBitmap() }
    }.getOrNull()

    /** Keeps [bytes] for [url]'s picture unless a larger copy is already kept. */
    private fun writeDisk(url: String, bytes: ByteArray, width: Int) {
        runCatching {
            val file = diskFile(url)
            val kept = if (file.isFile) runCatching { Image.makeFromEncoded(file.readBytes()).width }.getOrDefault(0) else 0
            if (width <= kept) return
            val part = java.io.File(file.path + ".part")
            part.writeBytes(bytes)
            java.nio.file.Files.move(part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Keeps a large copy of [thumbnail] on disk, for a song that has just been downloaded. */
    suspend fun keepForOffline(thumbnail: String) {
        if (thumbnail.isNotBlank()) load(thumbnail.atSize(1080))
    }

    /** Drops the least recently written covers past [DISK_LIMIT_BYTES]. Called once at start. */
    fun pruneDisk() {
        runCatching {
            val files = diskDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
            var total = 0L
            files.forEach { f ->
                total += f.length()
                if (total > DISK_LIMIT_BYTES) f.delete()
            }
        }
    }
    private val client by lazy { HttpClient(OkHttp) }

    /**
     * Fetches [url], retrying a transient failure.
     *
     * The retry is the point. Without it a single hiccup - a dropped connection, a slow CDN edge -
     * left that cover blank permanently, because the composable only asks again when the song
     * changes. Which is exactly the shape of "the art does not load for some songs": not a broken
     * URL, just one request that went wrong and was never repeated.
     *
     * Three attempts with a short backoff. Beyond that the cover genuinely is not coming, and
     * hammering the CDN for it helps nobody.
     */
    /**
     * Stands in for the network when set. Only the offscreen UI snapshots use it, so they can draw
     * a real cover without a connection.
     */
    @Volatile
    internal var substitute: ((String) -> ImageBitmap?)? = null

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        substitute?.let { return it(url) }
        images[url]?.let { return it }
        // A local song's cover is read from its file, not fetched - see LocalMusic.artwork.
        if (LocalMusic.isLocalArt(url)) {
            return withContext(Dispatchers.IO) { LocalMusic.artwork(url) }?.also { put(url, it) }
        }
        return withContext(Dispatchers.IO) {
            // A kept copy at least as large as asked for is used straight away.
            val wanted = Regex("""=w(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val kept = readDisk(url)
            if (kept != null && kept.width >= wanted) {
                put(url, kept)
                return@withContext kept
            }
            repeat(ATTEMPTS) { attempt ->
                val fetched = runCatching {
                    val bytes: ByteArray = client.get(url).body()
                    bytes to Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }.getOrNull()
                if (fetched != null) {
                    val (bytes, image) = fetched
                    put(url, image)
                    writeDisk(url, bytes, image.width)
                    return@withContext image
                }
                if (attempt < ATTEMPTS - 1) delay(RETRY_DELAY_MS * (attempt + 1))
            }
            // Offline, or the CDN refused: a smaller kept copy beats no cover at all.
            kept?.also { put(url, it) }
        }
    }

    /**
     * A decoded copy of this cover big enough to stand in for one [wantedPixels] across.
     *
     * The size floor is the point. Showing *any* cached copy meant a 96px thumbnail from a list row
     * was blown up to fill a 700px player for as long as the real one took to arrive - a full-screen
     * blur that reads as the app having broken rather than as loading. A blank tile is calmer than a
     * bad image, so anything under half the target is refused and the space stays empty until
     * something worth looking at arrives.
     */
    fun cachedVariant(baseUrl: String, wantedPixels: Int): ImageBitmap? {
        if (baseUrl.isBlank()) return null
        val prefix = baseUrl.sizeKey()
        val floor = wantedPixels / 2
        synchronized(images) {
            // Scanned rather than indexed. The map holds a hundred and twenty entries at most, so this is cheaper
            // than maintaining a second structure - and it runs once per cover, not per frame.
            return images.entries
                .filter { it.key.sizeKey() == prefix && it.value.width >= floor }
                // The smallest that clears the bar, so an upgrade still visibly sharpens rather than
                // starting from something already larger than needed.
                .minByOrNull { it.value.width }
                ?.value
        }
    }

    private fun put(url: String, image: ImageBitmap) {
        synchronized(images) {
            // Oldest out, rather than everything out. Clearing wholesale threw away covers that were
            // on screen, so a long scroll made the list refetch what it was already showing.
            images.remove(url)
            var total = images.values.sumOf { it.pixelBytes } + image.pixelBytes
            val oldestFirst = images.keys.iterator()
            while (oldestFirst.hasNext() && (images.size >= MAX_ENTRIES || total > MAX_PIXEL_BYTES)) {
                total -= images[oldestFirst.next()]?.pixelBytes ?: 0
                oldestFirst.remove()
            }
            images[url] = image
        }
    }

    /** The part of a URL that identifies the cover rather than the size asked for. */
    private fun String.sizeKey(): String = substringBefore("=w").substringBefore("=s")

    private const val DISK_LIMIT_BYTES = 200L * 1024 * 1024

    private const val ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 400L
}

/**
 * Asks the CDN for a cover at the size it will be drawn.
 *
 * YouTube hands back whatever thumbnail suited the response it came from - browse and search
 * payloads routinely carry 60-226px art - and using those verbatim means everything larger than a
 * list row is an upscale of a postage stamp. The size is part of the URL, so asking for a bigger one
 * costs nothing but writing a different number.
 *
 * The same reasoning as the Android app's own artwork sizing; the pattern is duplicated rather than
 * shared because that helper lives in the Android module.
 */
private fun String.atSize(pixels: Int): String = when {
    isBlank() -> this
    // Scaled at its own shape. This forced every image to a pixels x pixels crop ("-p"), which is
    // harmless for a square cover and wrecked an artist's wide banner: YouTube cut a square out of
    // the middle, and the artist header then showed a strip of that - "half a picture".
    contains("=w") && contains("-h") -> {
        val m = Regex("""=w(\d+)-h(\d+)""").find(this)
        val w = m?.groupValues?.get(1)?.toIntOrNull()
        val h = m?.groupValues?.get(2)?.toIntOrNull()
        if (w == null || h == null || w <= 0 || h <= 0 || w == h) {
            substringBefore("=w") + "=w$pixels-h$pixels-p-l90-rj"
        } else {
            val scale = pixels.toDouble() / maxOf(w, h)
            val sw = (w * scale).toInt().coerceAtLeast(1)
            val sh = (h * scale).toInt().coerceAtLeast(1)
            substringBefore("=w") + "=w$sw-h$sh-l90-rj"
        }
    }
    contains("=s") -> substringBefore("=s") + "=s$pixels"
    else -> this
}

/**
 * Two colours pulled out of a cover, for tinting the player behind it.
 *
 * Averaging every pixel gives mud - album art is mostly midtones, and the mean of a colourful image
 * is reliably grey. Instead the pixels are bucketed coarsely by hue and lightness, near-greys are
 * dropped, and the two fullest buckets win. That keeps the colour a person would name if asked what
 * colour the cover is, which is the point.
 *
 * Only a grid of samples is read rather than every pixel: a cover is a few hundred pixels square and
 * the answer does not change for reading all of them.
 */
fun ImageBitmap.dominantColours(): Pair<Color, Color> {
    val pixels = runCatching { toPixelMap() }.getOrNull() ?: return DEFAULT_COLOURS
    val buckets = HashMap<Int, MutableList<Color>>()
    val step = maxOf(1, minOf(width, height) / 32)

    var x = 0
    while (x < width) {
        var y = 0
        while (y < height) {
            val colour = runCatching { pixels[x, y] }.getOrNull()
            if (colour != null && colour.alpha > 0.5f) {
                val max = maxOf(colour.red, colour.green, colour.blue)
                val min = minOf(colour.red, colour.green, colour.blue)
                // Saturation and lightness both matter: a near-grey has nothing to contribute, and
                // near-black or near-white would swamp everything else since covers have a lot of both.
                if (max - min > 0.12f && max > 0.15f && min < 0.95f) {
                    val key = (colour.red * 4).toInt() * 100 + (colour.green * 4).toInt() * 10 +
                        (colour.blue * 4).toInt()
                    buckets.getOrPut(key) { mutableListOf() }.add(colour)
                }
            }
            y += step
        }
        x += step
    }

    val ranked = buckets.values.sortedByDescending { it.size }
    if (ranked.isEmpty()) return DEFAULT_COLOURS
    val first = ranked[0].average()
    val second = ranked.getOrNull(1)?.average() ?: first
    return first to second
}

/**
 * A second colour from the cover that is genuinely a different hue from [primary], or null when the
 * cover has only one.
 *
 * The theme needs this because deriving every accent from one hue is what makes a generated scheme
 * read as a single wash of colour. Material You does not do that - it pulls several colours out of
 * the image and builds primary and tertiary from different ones - and the difference between the
 * two approaches is the difference between "tinted" and "themed".
 *
 * Ranked clusters are searched in order of how much of the cover they occupy, and the first one far
 * enough round the wheel wins. Far enough is [MIN_HUE_SEPARATION] degrees: closer than that and the
 * two accents are indistinguishable on screen, which is worse than not having a second one, since
 * it costs a hue that could have been borrowed from elsewhere.
 */
fun ImageBitmap.secondaryHueSeed(primary: Color): Color? {
    val pixels = runCatching { toPixelMap() }.getOrNull() ?: return null
    val buckets = HashMap<Int, MutableList<Color>>()
    val step = maxOf(1, minOf(width, height) / 32)

    var x = 0
    while (x < width) {
        var y = 0
        while (y < height) {
            val colour = runCatching { pixels[x, y] }.getOrNull()
            if (colour != null && colour.alpha > 0.5f) {
                val max = maxOf(colour.red, colour.green, colour.blue)
                val min = minOf(colour.red, colour.green, colour.blue)
                // Stricter than the dominant pass: a second accent is only worth having if it is
                // actually colourful, where the primary may legitimately be a muted wash.
                if (max - min > 0.20f && max > 0.25f && min < 0.90f) {
                    val key = (colour.red * 4).toInt() * 100 + (colour.green * 4).toInt() * 10 +
                        (colour.blue * 4).toInt()
                    buckets.getOrPut(key) { mutableListOf() }.add(colour)
                }
            }
            y += step
        }
        x += step
    }

    val primaryHue = hueOf(primary) ?: return null
    return buckets.values
        .sortedByDescending { it.size }
        .asSequence()
        .map { it.average() }
        .firstOrNull { candidate ->
            val hue = hueOf(candidate) ?: return@firstOrNull false
            var delta = kotlin.math.abs(hue - primaryHue) % 360f
            if (delta > 180f) delta = 360f - delta
            delta >= MIN_HUE_SEPARATION
        }
}

private fun hueOf(colour: Color): Float? {
    val maxC = maxOf(colour.red, colour.green, colour.blue)
    val minC = minOf(colour.red, colour.green, colour.blue)
    val delta = maxC - minC
    if (delta < 1e-4f) return null
    val hue = when (maxC) {
        colour.red -> 60f * (((colour.green - colour.blue) / delta) % 6f)
        colour.green -> 60f * (((colour.blue - colour.red) / delta) + 2f)
        else -> 60f * (((colour.red - colour.green) / delta) + 4f)
    }
    return (hue + 360f) % 360f
}

/** Below this the two accents read as the same colour, and the second one is not worth having. */
private const val MIN_HUE_SEPARATION = 35f

private fun List<Color>.average(): Color = Color(
    red = sumOf { it.red.toDouble() }.toFloat() / size,
    green = sumOf { it.green.toDouble() }.toFloat() / size,
    blue = sumOf { it.blue.toDouble() }.toFloat() / size,
)

/** Used when a cover has no usable colour, or none has loaded yet. */
private val DEFAULT_COLOURS = Color(0xFF2A2A32) to Color(0xFF14141A)

/**
 * Whether the top-left corner of [url]'s image is light, as it shows when cropped to [aspect]
 * (width / height) from its top centre - for a control drawn over that corner, such as the artist
 * banner's back button, which was always white and vanished on a bright photo. Null until known.
 */
@Composable
fun rememberTopLeftIsLight(url: String?, aspect: Float): Boolean? {
    var light by remember(url, aspect) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(url, aspect) {
        val image = url?.takeIf { it.isNotBlank() }?.let { ArtworkCache.load(it.atSize(256)) } ?: return@LaunchedEffect
        light = withContext(Dispatchers.Default) { image.cornerLuminance(aspect) > 0.55f }
    }
    return light
}

/** Mean luminance of the top-left corner of this image cropped to [aspect] from its top centre. */
private fun ImageBitmap.cornerLuminance(aspect: Float): Float {
    val pixels = runCatching { toPixelMap() }.getOrNull() ?: return 0f
    val imageAspect = width.toFloat() / height
    // The visible crop: full width from the top when the image is taller than the box, else a
    // centred slice of full height.
    val cropW = if (imageAspect < aspect) width.toFloat() else height * aspect
    val cropH = if (imageAspect < aspect) width / aspect else height.toFloat()
    val cropX = (width - cropW) / 2f
    var sum = 0f
    var n = 0
    val x1 = (cropX + cropW * 0.3f).toInt().coerceAtMost(width)
    val y1 = (cropH * 0.25f).toInt().coerceAtMost(height)
    val step = maxOf(1, (x1 - cropX.toInt()) / 24)
    var x = cropX.toInt()
    while (x < x1) {
        var y = 0
        while (y < y1) {
            val c = pixels[x, y]
            sum += 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
            n++
            y += step
        }
        x += step
    }
    return if (n == 0) 0f else sum / n
}

/** The cover's colours, recomputed only when the URL changes. */
@Composable
fun rememberArtworkColours(url: String?): Pair<Color, Color> {
    var colours by remember(url) { mutableStateOf(DEFAULT_COLOURS) }
    LaunchedEffect(url) {
        // A small copy is plenty for sampling colour, and it is usually already cached from a row.
        colours = url?.takeIf { it.isNotBlank() }
            ?.let { ArtworkCache.load(it.atSize(128))?.dominantColours() }
            ?: DEFAULT_COLOURS
    }
    return colours
}

/**
 * The cover's second usable hue, for the theme's tertiary accent - see [secondaryHueSeed].
 *
 * Null while the cover is loading, and null for a cover that has only one colour in it, which is
 * the signal for the scheme to fall back to a shift off the primary rather than inventing a
 * difference the artwork does not have.
 */
@Composable
fun rememberArtworkAccent(url: String?): Color? {
    var accent by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        accent = url?.takeIf { it.isNotBlank() }?.let { address ->
            // The same cached 128px copy the dominant pass uses, so this costs a second scan of a
            // small bitmap rather than a second download.
            ArtworkCache.load(address.atSize(128))?.let { image ->
                image.secondaryHueSeed(image.dominantColours().first)
            }
        }
    }
    return accent
}

/**
 * A square cover, or a blank tile of the right size while it loads or if it never arrives.
 *
 * Always occupying its space matters more than it sounds: a cover that appears only once loaded
 * would reflow every row it lands in, so the list would jitter as it scrolled.
 */
@Composable
fun Artwork(
    url: String?,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    /**
     * Corner rounding.
     *
     * A parameter because the right amount depends entirely on how big the thing is drawn. Four
     * pixels reads as "slightly softened" on a 44dp queue row and as a hard square on a cover
     * filling half the window - the radius has to grow with the art for the corner to look the same.
     */
    cornerRadius: Dp = 4.dp,
    /** Drawn this tall when not square - a banner. Defaults to [size], a square. */
    height: Dp = size,
    /** Which part survives the crop when the image and the box differ in shape. */
    alignment: Alignment = Alignment.Center,
) {
    // Requested at twice the drawn size, so it stays sharp on a high-DPI display and when the
    // window is scaled. Rounded up to a sane step rather than the exact dp, so a handful of sizes
    // are requested across the app instead of one cached image per pixel dimension.
    val longest = maxOf(size, height)
    val pixels = remember(longest) { ((longest.value.toInt() * 2 + 63) / 64 * 64).coerceIn(64, 1280) }

    // Starts from whatever is already decoded for this cover at *any* size, which is usually the
    // small one a list row fetched a moment ago. Opening the player then shows the art immediately
    // and sharpens when the large version lands, instead of showing an empty tile for as long as a
    // fresh full-size download takes. The size is part of the URL, so the large and small versions
    // are different requests and nothing else would connect them.
    var image by remember(url, pixels) {
        mutableStateOf(url?.let { ArtworkCache.cachedVariant(it, pixels) })
    }
    LaunchedEffect(url, pixels) {
        url?.takeIf { it.isNotBlank() }?.let { ArtworkCache.load(it.atSize(pixels)) }
            // Only replaced on success: a failed upgrade must not blank a placeholder that is
            // already showing something correct, merely lower resolution.
            ?.let { image = it }
    }

    Box(
        modifier = modifier
            .size(width = size, height = height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier.size(width = size, height = height),
            )
        }
    }
}
