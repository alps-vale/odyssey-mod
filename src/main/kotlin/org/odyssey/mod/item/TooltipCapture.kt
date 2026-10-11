package org.odyssey.mod.item

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.gui.render.state.GuiRenderState
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import org.joml.Vector2i
import org.odyssey.mod.network.MAX_ITEM_SHARES
import org.odyssey.mod.network.MAX_TOOLTIP_PNG_BYTES
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal object TooltipCapture {
    // One message's tooltips render together. A slot covers the colour/depth target, its GPU
    // readback and the PNG encoder, so a timed-out result does not free capacity early.
    private val slots = CaptureSlots(MAX_ITEM_SHARES)

    /** Call on the render thread. Completes with `null` if no image is ready within five seconds. */
    fun capture(stack: ItemStack): CompletableFuture<String?> {
        val result = CompletableFuture<String?>()
        slots.submit { release ->
            val releaseOnRenderThread = { Minecraft.getInstance().execute { release() } }
            if (result.isDone) release() else render(stack, result, releaseOnRenderThread)
        }
        return result.completeOnTimeout(null, 5, TimeUnit.SECONDS)
    }

    private fun render(stack: ItemStack, result: CompletableFuture<String?>, release: () -> Unit) {
        val minecraft = Minecraft.getInstance()
        var target: TextureTarget? = null
        try {
            RenderSystem.assertOnRenderThread()
            val lines = stack.getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL)
            require(lines.isNotEmpty() && lines.size <= 80) { "Tooltip line limit exceeded" }
            val components = lines.map { ClientTooltipComponent.create(it.visualOrderText) }
            // Custom Wynncraft frames extend farther than the vanilla tooltip border.
            val width = components.maxOf { it.getWidth(minecraft.font) } + 32
            val height = components.sumOf { it.getHeight(minecraft.font) } + 34
            require(width in 1..512 && height in 1..1024) { "Tooltip image bounds exceeded" }
            val imageTarget = TextureTarget("Odyssey item tooltip", width * 2, height * 2, true)
            target = imageTarget
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                checkNotNull(imageTarget.colorTexture), 0, checkNotNull(imageTarget.depthTexture), 1.0,
            )
            val state = GuiRenderState()
            val graphics = GuiGraphics(minecraft, state, 0, 0)
            graphics.renderTooltip(
                minecraft.font, components, 0, 0,
                { _, _, _, _, _, _ -> Vector2i(16, 16) }, stack.get(DataComponents.TOOLTIP_STYLE),
            )
            RenderSystem.backupProjectionMatrix()
            try {
                TooltipGuiRenderer(state, imageTarget).use { it.render(checkNotNull(RenderSystem.getShaderFog())) }
            } finally {
                RenderSystem.restoreProjectionMatrix()
            }
            // This target only contains the tooltip. The world, UI and player screen are never copied.
            Screenshot.takeScreenshot(imageTarget) { nativeImage ->
                var encoding: CompletableFuture<String?>? = null
                try {
                    val pixels = nativeImage.pixels
                    val imageWidth = nativeImage.width
                    val imageHeight = nativeImage.height
                    encoding = CompletableFuture.supplyAsync {
                        val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
                        image.setRGB(0, 0, imageWidth, imageHeight, pixels, 0, imageWidth)
                        val bytes = ByteArrayOutputStream().use { output ->
                            check(ImageIO.write(image, "png", output))
                            output.toByteArray()
                        }
                        if (bytes.size > MAX_TOOLTIP_PNG_BYTES) null else Base64.getEncoder().encodeToString(bytes)
                    }
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                } finally {
                    nativeImage.close()
                    imageTarget.destroyBuffers()
                }
                // The PNG worker still holds this tooltip's pixels, so it keeps the slot until done.
                encoding?.whenComplete { png, error ->
                    if (error == null) result.complete(png) else result.completeExceptionally(error)
                    release()
                } ?: release()
            }
        } catch (error: Throwable) {
            // Optional mods render tooltip lines too; a linkage error there must not escape chat handling.
            target?.destroyBuffers()
            result.completeExceptionally(error)
            release()
        }
    }
}

internal class TooltipGuiRenderer(state: GuiRenderState, val target: RenderTarget) : GuiRenderer(
    state,
    Minecraft.getInstance().renderBuffers().bufferSource(),
    Minecraft.getInstance().gameRenderer.submitNodeStorage,
    Minecraft.getInstance().gameRenderer.featureRenderDispatcher,
    emptyList(),
)
