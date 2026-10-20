package com.example.parallaxwallpaper.depth

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A static, continuous displacement field shared by old and newly generated photos. */
object DepthField {
    // In UV units, each partial derivative is at most six. At maximum two-axis
    // offset (.035, .035), inverse sampling is a contraction <= .42, so surfaces
    // cannot fold or switch between multiple ray intersections as the phone moves.
    const val MAX_UV_SLOPE = 6f

    fun prepare(input: FloatArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0 && input.size == width * height)
        val clean = FloatArray(input.size) { i ->
            input[i].takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        }
        // Suppress depth speckles, not RGB detail. Keep the same UV footprint
        // across resolutions; do not normalize again and amplify residual noise.
        val sigma = (min(width, height) / 259f).coerceAtLeast(0.5f)
        val smooth = blur(clean, width, height, sigma)
        val lower = smooth.copyOf()
        val upper = smooth.copyOf()
        val dx = MAX_UV_SLOPE / width
        val dy = MAX_UV_SLOPE / height
        // Lower/upper Lipschitz envelopes (weighted Manhattan distance transform).
        // Their midpoint preserves the depth scale while bounding displacement
        // gradients at silhouettes. No tilt-dependent masks or region thresholds.
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (x > 0) {
                lower[i] = min(lower[i], lower[i - 1] + dx)
                upper[i] = max(upper[i], upper[i - 1] - dx)
            }
            if (y > 0) {
                lower[i] = min(lower[i], lower[i - width] + dy)
                upper[i] = max(upper[i], upper[i - width] - dy)
            }
        }
        for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
            val i = y * width + x
            if (x + 1 < width) {
                lower[i] = min(lower[i], lower[i + 1] + dx)
                upper[i] = max(upper[i], upper[i + 1] - dx)
            }
            if (y + 1 < height) {
                lower[i] = min(lower[i], lower[i + width] + dy)
                upper[i] = max(upper[i], upper[i + width] - dy)
            }
        }
        for (i in smooth.indices) smooth[i] = (lower[i] + upper[i]) * 0.5f
        // Smooth envelope joins; positive averaging preserves the slope bound.
        return blur(smooth, width, height, sigma)
    }

    /** 16-bit depth in RG, reconstructed AFTER hardware linear filtering. */
    fun pack(depth: FloatArray): ByteArray = ByteArray(depth.size * 4).also { out ->
        for (i in depth.indices) {
            val value = (depth[i].coerceIn(0f, 1f).toDouble() * 65535.0).roundToInt()
            out[i * 4] = (value ushr 8).toByte()
            out[i * 4 + 1] = value.toByte()
            out[i * 4 + 3] = 255.toByte()
        }
    }

    private fun blur(input: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val radius = kotlin.math.ceil(sigma * 3).toInt()
        val kernel = FloatArray(radius * 2 + 1) { i ->
            val distance = (i - radius).toFloat()
            exp(-distance * distance / (2 * sigma * sigma))
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        val temp = FloatArray(input.size)
        val out = FloatArray(input.size)
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0f
            for (k in -radius..radius) value += input[y * width + (x + k).coerceIn(0, width - 1)] * kernel[k + radius]
            temp[y * width + x] = value
        }
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0f
            for (k in -radius..radius) value += temp[(y + k).coerceIn(0, height - 1) * width + x] * kernel[k + radius]
            out[y * width + x] = value.coerceIn(0f, 1f)
        }
        return out
    }
}
