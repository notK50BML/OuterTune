/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O⁠ute⁠rTu⁠ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import coil3.size.Dimension
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.media3.common.util.BitmapLoader
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.imageLoader
import coil3.key.Keyer
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.R
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import kotlin.math.min

class CoilBitmapLoader @Inject constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(coilCoroutine),
    private val data: LocalArtworkPath = LocalArtworkPath(null),
    /** The request's own, for the size it will be shown at - see [fetch]. */
    private val options: Options? = null,
) : Fetcher, BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future {
            BitmapFactory.decodeByteArray(data, 0, data.size) ?: drawPlaceholder(context)
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future {
            try {
                // local images
                val result = if (uri.toString().startsWith("/storage/")) {
                    context.imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(LocalArtworkPath(uri.toString()))
                            .allowHardware(false)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build()
                    )
                } else {
                    context.imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(uri)
                            .allowHardware(false)
                            .build()
                    )
                }
                if (result is ErrorResult) {
                    reportException(ExecutionException(result.throwable))
                    return@future drawPlaceholder(context)
                }

                result.image!!.toBitmap()
            } catch (e: Exception) {
                reportException(ExecutionException(e))
                return@future drawPlaceholder(context)
            }
        }

    override suspend fun fetch(): FetchResult? {
        return try {
            if (data.path == null) {
                null
            } else {
                // Two different things end up here: a downloaded thumbnail is already an image
                // file on disk (see DownloadUtil.downloadThumbnail), while a locally-scanned
                // song's own path is an audio file whose cover art has to be pulled out of its
                // tags. decodeFile only reads image headers, so trying it first is cheap and
                // correctly picks the downloaded-thumbnail case without needing a path prefix
                // check (that thumbnail lives under the app's private files dir, not /storage/).
                //
                // Decoded at about the size it is shown at, not in full and then scaled: a saved cover
                // is 1080px and a file's own can be 3000, which is 4.6MB and 36MB as bitmaps - for a
                // row thumbnail 150px across, and they used to sit in the memory cache at that size.
                val target = targetSize()
                var sampled = false
                fun decode(read: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
                    if (target == null) return read(BitmapFactory.Options())
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    read(bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= target.first && bounds.outHeight / (sample * 2) >= target.second) {
                        sample *= 2
                    }
                    if (sample > 1) sampled = true
                    return read(BitmapFactory.Options().apply { inSampleSize = sample })
                }
                var image: Bitmap? = decode { BitmapFactory.decodeFile(data.path, it) }

                if (image == null) {
                    image = try {
                        val mData = MediaMetadataRetriever()
                        mData.setDataSource(data.path)
                        val art = mData.embeddedPicture
                        mData.release()
                        art?.let { bytes -> decode { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) } }
                    } catch (e: Exception) {
                        null
                    }
                }
                image = image ?: drawPlaceholder(context)

                if (data.x + data.y > 0) {
                    var realX = data.x
                    var realY = data.y

                    // scale maintaining aspect ratio
                    if (image.width != image.height) {
                        val frameW = data.x
                        val frameH = data.y
                        val imgW = image.width
                        val imgH = image.height

                        val scaleX = frameW.toFloat() / imgW
                        val scaleY = frameH.toFloat() / imgH
                        val scale = minOf(scaleX, scaleY)

                        realX = (imgW * scale).toInt()
                        realY = (imgH * scale).toInt()
                    }

                    image = image.scale(realX, realY)
                }

                ImageFetchResult(
                    image = image.asImage(),
                    isSampled = sampled,
                    dataSource = DataSource.DISK
                )
            }
        } catch (e: Exception) {
            reportException(e)
            ImageFetchResult(
                image = drawPlaceholder(context).asImage(),
                isSampled = false,
                dataSource = DataSource.MEMORY
            )
        }
    }

    /**
     * The pixels this image will be shown at: the size the artwork asked for, or failing that the
     * request's own (Coil's, from the view). Null when neither says - the whole image, as before.
     */
    private fun targetSize(): Pair<Int, Int>? {
        if (data.x > 0 && data.y > 0) return data.x to data.y
        val size = options?.size ?: return null
        val w = (size.width as? Dimension.Pixels)?.px ?: return null
        val h = (size.height as? Dimension.Pixels)?.px ?: return null
        return w to h
    }

    companion object {
        // TODO: re eval dimens after a few months
        /**
         * Draw a centered square app icon with the maximum possible size while maintaining aspect ratio.
         *
         * @param context
         * @param x Desired final x dimension
         * @param y Desired final y dimension
         * @param size Percentage size of valid draw frame. Must be a value between 0.0 and 1.0. For example, 0.8
         *      means that inner frame should be 80% of the size of the final frame, and centered within that frame.
         */
        // 512 rather than 2000: an icon on a plain square, and every missing cover drew one - 16MB
        // each at 2000px, held in the memory cache like any other image.
        fun drawPlaceholder(context: Context, x: Int = 512, y: Int = 512, size: Float = 0.8f): Bitmap {
            val padding = size.coerceIn(0f, 1f)
            val innerRecWidth = x * padding
            val innerRecHeight = y * padding

            val squareLength = min(innerRecWidth, innerRecHeight).toInt()
            val squareLeft = ((x - squareLength) / 2)
            val squareTop = ((y - squareLength) / 2)

            val drawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.placeholder_icon)
            val bitmap = Bitmap.createBitmap(x, y, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            drawable?.setBounds(squareLeft, squareTop, squareLeft + squareLength, squareTop + squareLength)
            drawable?.draw(canvas)
            return bitmap
        }
    }

    class Factory(
        private val context: Context,
    ) : Fetcher.Factory<LocalArtworkPath> {
        override fun create(data: LocalArtworkPath, options: Options, imageLoader: ImageLoader): Fetcher? {
            return CoilBitmapLoader(context, data = data, options = options)
        }
    }
}

class LocalArtworkPathKeyer : Keyer<LocalArtworkPath> {
    override fun key(
        data: LocalArtworkPath,
        options: Options
    ): String? {
        return data.path + ";" + data.x + ";" + data.y
    }

}

data class LocalArtworkPath(val path: String?, val x: Int = -1, val y: Int = -1)
