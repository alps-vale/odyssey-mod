package org.odyssey.smoke.mixin;

import org.odyssey.smoke.ItemRenderSmoke;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wynntils posts tooltip events only on Wynncraft with a player, which the smoke client never has. */
@Pseudo
@Mixin(targets = "com.wynntils.core.events.MixinHelper", remap = false)
public abstract class WynntilsTooltipEventsMixin {
    @Inject(method = "post", at = @At("HEAD"), cancellable = true, remap = false)
    private static void odyssey$postOffServer(@Coerce Object event, CallbackInfo callback) {
        if (!ItemRenderSmoke.wynntilsEventsOffServer) return;
        try {
            Class.forName("com.wynntils.core.events.MixinHelper")
                .getMethod("postAlways", Class.forName("net.neoforged.bus.api.Event"))
                .invoke(null, event);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Wynntils event bus changed", failure);
        }
        callback.cancel();
    }
}
