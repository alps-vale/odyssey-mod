package org.odyssey.mod.mixin

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.gui.render.GuiRenderer
import org.odyssey.mod.item.TooltipGuiRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(GuiRenderer::class)
abstract class TooltipGuiRendererMixin {
    @ModifyExpressionValue(method = ["draw"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")])
    private fun `odyssey$tooltipTarget`(original: RenderTarget): RenderTarget =
        ((this as Any) as? TooltipGuiRenderer)?.target ?: original

    @ModifyExpressionValue(method = ["draw", "executeDrawRange"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getWidth()I")])
    private fun `odyssey$tooltipWidth`(original: Int): Int =
        ((this as Any) as? TooltipGuiRenderer)?.target?.width ?: original

    @ModifyExpressionValue(method = ["draw", "executeDrawRange"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getHeight()I")])
    private fun `odyssey$tooltipHeight`(original: Int): Int =
        ((this as Any) as? TooltipGuiRenderer)?.target?.height ?: original

    @ModifyExpressionValue(method = ["draw", "executeDrawRange", "getGuiScaleInvalidatingItemAtlasIfChanged"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getGuiScale()I")])
    private fun `odyssey$tooltipScale`(original: Int): Int =
        if ((this as Any) is TooltipGuiRenderer) 2 else original
}
