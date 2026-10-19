package org.odyssey.mod.item

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Encodes the drawn part of a top-down, premultiplied ARGB image as a straight-alpha PNG.
 *
 * GUI drawing blends into a transparent target, which leaves premultiplied colour. The PNG covers
 * the smallest rectangle holding every pixel with non-zero alpha and keeps each pixel's alpha, so
 * faint shadows stay and undrawn margins stay transparent. Returns `null` when nothing was drawn.
 */
internal fun encodeDrawnPng(pixels: IntArray, width: Int, height: Int): ByteArray? {
    require(width > 0 && height > 0 && pixels.size == width * height)
    fun drawn(x: Int, y: Int) = pixels[y * width + x] ushr 24 != 0
    var left = width
    var right = -1
    var top = -1
    var bottom = -1
    for (y in 0 until height) {
        val first = (0 until width).firstOrNull { drawn(it, y) } ?: continue
        val last = (width - 1 downTo first).first { drawn(it, y) }
        if (top < 0) top = y
        bottom = y
        left = minOf(left, first)
        right = maxOf(right, last)
    }
    if (top < 0) return null
    val cropWidth = right - left + 1
    val cropHeight = bottom - top + 1
    val straight = IntArray(cropWidth * cropHeight) { index ->
        unpremultiply(pixels[(top + index / cropWidth) * width + left + index % cropWidth])
    }
    val image = BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, cropWidth, cropHeight, straight, 0, cropWidth)
    return ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }
}

private fun unpremultiply(argb: Int): Int {
    val alpha = argb ushr 24
    if (alpha == 0) return 0
    if (alpha == 255) return argb
    fun channel(shift: Int) = minOf(255, ((argb ushr shift and 0xFF) * 255 + alpha / 2) / alpha) shl shift
    return (argb and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
}
