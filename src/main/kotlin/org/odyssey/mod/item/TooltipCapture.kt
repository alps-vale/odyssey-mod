package org.odyssey.mod.item

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.gui.render.state.GuiRenderState
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner
import net.minecraft.core.component.DataComponents
import net.minecraft.util.ARGB
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import org.joml.Vector2i
import org.odyssey.mod.network.MAX_ITEM_SHARES
import org.odyssey.mod.network.MAX_TOOLTIP_PNG_BYTES
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object TooltipCapture {
    // One message's tooltips render together and one more message's may wait. A slot covers the
    // colour/depth target, its GPU readback and the PNG encoder, so a timed-out result does not free
    // capacity early.
    private val slots = CaptureSlots(limit = MAX_ITEM_SHARES, waitingLimit = MAX_ITEM_SHARES)

    /**
     * The GUI size the window reports to the render thread while a capture lays out its tooltip.
     * Tooltip mods such as Wynntils size and place tooltips for the window, which would shrink
     * this tooltip and push its top out of the image. Other threads always see the real window.
     */
    var layoutSize: Vector2i? = null
        get() = field?.takeIf { RenderSystem.isOnRenderThread() }
        private set

    /**
     * Call on the render thread. Completes with `null` at once when captures are saturated, or if no
     * image is ready within five seconds.
     */
    fun capture(stack: ItemStack): CompletableFuture<String?> =
        slots.capture(TimeUnit.SECONDS.toMillis(5)) { result, release ->
            // Always a later client turn: execute() runs inline on the render thread, inside the
            // readback callback that still owns its mapped buffer until we return.
            render(stack, result) { Minecraft.getInstance().schedule { release() } }
        }

    private fun render(stack: ItemStack, result: CompletableFuture<String?>, release: () -> Unit) {
        val minecraft = Minecraft.getInstance()
        var target: TextureTarget? = null
        var readback: GpuBuffer? = null
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
            val colorTexture = checkNotNull(imageTarget.colorTexture)
            val device = RenderSystem.getDevice()
            device.createCommandEncoder().clearColorAndDepthTextures(
                colorTexture, 0, checkNotNull(imageTarget.depthTexture), 1.0,
            )
            val state = GuiRenderState()
            layoutSize = Vector2i(width, height)
            try {
                // The default positioner, and any that replaces it, puts the tooltip at (16, 16).
                GuiGraphics(minecraft, state, 0, 0).renderTooltip(
                    minecraft.font, components, 4, 28, DefaultTooltipPositioner.INSTANCE,
                    stack.get(DataComponents.TOOLTIP_STYLE),
                )
            } finally {
                layoutSize = null
            }
            RenderSystem.backupProjectionMatrix()
            try {
                TooltipGuiRenderer(state, imageTarget).use { it.render(checkNotNull(RenderSystem.getShaderFog())) }
            } finally {
                RenderSystem.restoreProjectionMatrix()
            }
            val pixelWidth = imageTarget.width
            val pixelHeight = imageTarget.height
            val pixelSize = colorTexture.format.pixelSize()
            val buffer = device.createBuffer(
                { "Odyssey item tooltip readback" }, GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST,
                pixelWidth.toLong() * pixelHeight * pixelSize,
            )
            readback = buffer
            val encoder = device.createCommandEncoder()
            // This target only contains the tooltip. The world, UI and player screen are never copied.
            // Screenshot.takeScreenshot forces every pixel opaque, which would turn undrawn space black.
            encoder.copyTextureToBuffer(colorTexture, buffer, 0, {
                var encoding: CompletableFuture<String?>? = null
                try {
                    val pixels = encoder.mapBuffer(buffer, true, false).use { view ->
                        val data = view.data()
                        IntArray(pixelWidth * pixelHeight) { index ->
                            val textureRow = pixelHeight - 1 - index / pixelWidth
                            ARGB.fromABGR(data.getInt((textureRow * pixelWidth + index % pixelWidth) * pixelSize))
                        }
                    }
                    encoding = CompletableFuture.supplyAsync {
                        encodeDrawnPng(pixels, pixelWidth, pixelHeight)
                            ?.takeIf { it.size <= MAX_TOOLTIP_PNG_BYTES }
                            ?.let(Base64.getEncoder()::encodeToString)
                    }
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                } finally {
                    buffer.close()
                    imageTarget.destroyBuffers()
                }
                // The PNG worker still holds this tooltip's pixels, so it keeps the slot until done.
                encoding?.whenComplete { png, error ->
                    if (error == null) result.complete(png) else result.completeExceptionally(error)
                    release()
                } ?: release()
            }, 0)
        } catch (error: Throwable) {
            // Optional mods render tooltip lines too; a linkage error there must not escape chat handling.
            readback?.close()
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
