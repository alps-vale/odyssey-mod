package org.odyssey.mod.mixin

import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.chat.ChatListener
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import org.odyssey.mod.OdysseyMod
import org.odyssey.mod.OdysseyDiagnostics
import org.odyssey.mod.chat.GuildChatParser
import org.odyssey.mod.chat.GuildChatDecorator
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientPacketListener::class)
abstract class ClientPacketListenerMixin {
    @WrapOperation(
        method = ["handleSystemChat"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/chat/ChatListener;handleSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
        )],
    )
    private fun `odyssey$retainGuildHeader`(
        listener: ChatListener,
        message: Component,
        overlay: Boolean,
        original: Operation<Void>,
    ) {
        GuildChatDecorator.withGuildMessage(message) { original.call(listener, message, overlay) }
    }

    @Inject(
        method = ["handleSystemChat"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;getChatListener()Lnet/minecraft/client/multiplayer/chat/ChatListener;",
        )],
    )
    private fun `odyssey$observeGuildChat`(
        packet: ClientboundSystemChatPacket,
        callback: CallbackInfo,
    ) = OdysseyDiagnostics.callback("guild chat observation") {
        if (packet.overlay()) return@callback
        val parsed = GuildChatParser.parse(packet.content()) ?: return@callback
        OdysseyMod.observeGuildMessage(parsed.authorUsername, parsed.content, packet.content().siblings.drop(5))
    }
}
