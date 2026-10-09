package org.odyssey.mod.mixin

import net.minecraft.client.GuiMessage
import net.minecraft.client.gui.Font
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
import org.spongepowered.asm.mixin.injection.Redirect

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

    @Redirect(
        method = ["addMessageToDisplayQueue"],
        at = At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/GuiMessage;splitLines(Lnet/minecraft/client/gui/Font;I)Ljava/util/List;",
        ),
    )
    private fun `odyssey$wrapBridgeContinuations`(
        message: GuiMessage,
        font: Font,
        maxWidth: Int,
    ): List<FormattedCharSequence> = OdysseyDiagnostics.callback("bridge continuation wrapping") {
        val initialLines = message.splitLines(font, maxWidth)
        val prefix = BridgeChatRenderer.continuationPrefix(message.content()) ?: return@callback initialLines
        ChatBridgeLineWrapping.wrap(initialLines, message, font, maxWidth, prefix)
    }
}
