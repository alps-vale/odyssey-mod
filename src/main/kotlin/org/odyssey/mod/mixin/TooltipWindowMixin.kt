package org.odyssey.mod.mixin

import com.llamalad7.mixinextras.injector.ModifyReturnValue
import com.mojang.blaze3d.platform.Window
import org.odyssey.mod.item.TooltipCapture
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(Window::class)
abstract class TooltipWindowMixin {
    @ModifyReturnValue(method = ["getGuiScaledWidth"], at = [At("RETURN")])
    private fun `odyssey$tooltipWidth`(original: Int): Int = TooltipCapture.layoutSize?.x ?: original

    @ModifyReturnValue(method = ["getGuiScaledHeight"], at = [At("RETURN")])
    private fun `odyssey$tooltipHeight`(original: Int): Int = TooltipCapture.layoutSize?.y ?: original
}
