package org.odyssey.mod.mixin

import net.minecraft.client.GuiMessage
import com.llamalad7.mixinextras.sugar.Local
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import org.odyssey.mod.OdysseyDiagnostics
import org.odyssey.mod.chat.BridgeChatRenderer
import org.odyssey.mod.chat.ChatBridgeLineWrapping
import org.odyssey.mod.chat.GuildChatDecorator
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyVariable

@Mixin(ChatComponent::class)
abstract class ChatComponentMixin {
    @ModifyVariable(
        method = ["addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V"],
        at = At("HEAD"),
        argsOnly = true,
        ordinal = 0,
    )
    private fun `odyssey$decorateGuildChat`(component: Component): Component =
        OdysseyDiagnostics.callback("guild chat decoration") {
            GuildChatDecorator.decorate(component)
        }

    // Transform the stored result so other mods can still redirect the wrapping call.
    @ModifyVariable(
        method = ["addMessageToDisplayQueue"],
        at = At("STORE"),
        ordinal = 0,
    )
    private fun `odyssey$wrapBridgeContinuations`(
        initialLines: List<FormattedCharSequence>,
        message: GuiMessage,
        @Local(ordinal = 0) maxWidth: Int,
    ): List<FormattedCharSequence> = OdysseyDiagnostics.callback("bridge continuation wrapping") {
        val prefix = BridgeChatRenderer.continuationPrefix(message.content()) ?: return@callback initialLines
        ChatBridgeLineWrapping.wrap(initialLines, message, Minecraft.getInstance().font, maxWidth, prefix)
    }
}
