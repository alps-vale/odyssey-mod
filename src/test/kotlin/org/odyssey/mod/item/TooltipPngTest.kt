package org.odyssey.mod.item

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TooltipPngTest {
    @Test
    fun `drawn pixels keep their bounds, colours and alpha while undrawn space is dropped`() {
        val width = 10
        val height = 8
        val pixels = IntArray(width * height)
        pixels[1 * width + 2] = 0xFF000000.toInt()
        pixels[3 * width + 4] = 0xFFAA00AA.toInt()
        pixels[4 * width + 5] = 0x80402000.toInt()
        pixels[5 * width + 6] = 0x20000000

        val image = ImageIO.read(ByteArrayInputStream(assertNotNull(encodeDrawnPng(pixels, width, height))))

        assertEquals(5 to 5, image.width to image.height)
        assertEquals(0xFF000000.toInt(), image.getRGB(0, 0))
        assertEquals(0xFFAA00AA.toInt(), image.getRGB(2, 2))
        assertEquals(0x80804000.toInt(), image.getRGB(3, 3), "premultiplied colour is stored straight")
        assertEquals(0x20000000, image.getRGB(4, 4))
        assertEquals(0, image.getRGB(4, 0) ushr 24)
        assertEquals(0, image.getRGB(0, 4) ushr 24)
    }

    @Test
    fun `a capture with nothing drawn has no image`() {
        assertNull(encodeDrawnPng(IntArray(12), 4, 3))
    }
}
