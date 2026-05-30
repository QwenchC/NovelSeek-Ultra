package com.example.novelseek_ultra.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

object ImageUtils {

    /**
     * Normalize generated image bytes before they're base64'd into app state / chapter files.
     *
     * ComfyUI's SaveImage node returns full-resolution **PNG** (several MB each). Those base64
     * strings get inlined into `Project.cover_images` (and illustration/promo records), and
     * `AppRepository.saveStateToDisk` re-serializes the *entire* app state to a single in-memory
     * String on every mutation — so a couple of ComfyUI covers push the state past ~30MB and the
     * `Json.encodeToString` allocation throws OutOfMemoryError (crash on generate / on set-default).
     * Pollinations didn't trip this because it already returns compressed JPEGs.
     *
     * We decode, optionally downscale so the longest edge is <= [maxLongestEdge], and re-encode as
     * JPEG — typically a 5–10x size reduction. This also shrinks the bitmaps the UI decodes for
     * display. On any failure (decode error, OOM during decode) we return the original bytes so
     * image generation is never blocked, and if JPEG somehow ends up larger we keep the smaller one.
     */
    fun compressForStorage(
        bytes: ByteArray,
        maxLongestEdge: Int = 1920,
        quality: Int = 85,
    ): ByteArray {
        return try {
            // First decode just the bounds so we can pick an inSampleSize and avoid allocating a
            // huge bitmap for an oversized source.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val srcLongest = max(bounds.outWidth, bounds.outHeight)
            if (srcLongest <= 0) return bytes

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(srcLongest, maxLongestEdge)
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes

            // After sub-sampling the longest edge may still exceed the cap (inSampleSize is
            // power-of-2), so scale the rest of the way down precisely.
            val longest = max(decoded.width, decoded.height)
            val target = if (longest > maxLongestEdge) {
                val ratio = maxLongestEdge.toFloat() / longest
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else decoded

            val out = ByteArrayOutputStream(bytes.size / 4 + 1024)
            target.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (target !== decoded) target.recycle()
            decoded.recycle()

            val result = out.toByteArray()
            if (result.isNotEmpty() && result.size < bytes.size) result else bytes
        } catch (_: Throwable) {
            bytes
        }
    }

    private fun sampleSizeFor(srcLongest: Int, maxLongestEdge: Int): Int {
        var sample = 1
        // Halve until the sub-sampled longest edge is within ~2x of the target, then let
        // createScaledBitmap finish — keeps quality while bounding the decoded bitmap size.
        while (srcLongest / (sample * 2) >= maxLongestEdge) sample *= 2
        return sample
    }
}
